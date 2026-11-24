package com.xundao.yongwuzhijing

import android.content.Context
import android.util.Log
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import kotlinx.coroutines.runBlocking

/**
 * 带工具调用的本地推理入口。
 *
 * 为什么不是改 LlamaEngine：LlamaEngine 走的是 llama.cpp 的
 * 「processUserPrompt → generateNextToken」逐 token 流式接口，
 * 那个接口没有 tools 参数，也不会解析 tool_calls。
 *
 * 这里另走一条路：直接调 native 的整段生成（ai_chat_tools.cpp），
 * 用 llama.cpp 的 common_chat_templates_apply(tools) + common_chat_parse，
 * 输入输出都是 OpenAI 兼容 JSON —— 这样 ModelBridge 那边改两行就能接上。
 *
 * 判据：能不能拿到 tool_calls。拿不到就等于没这功能。
 */
object LlamaToolEngine {

    private const val TAG = "LlamaToolEngine"

    @Volatile private var inited = false
    @Volatile private var lastError: String = ""

    private external fun nativeGenerateWithTools(
        messagesJson: String,
        toolsJson: String,
        nPredict: Int
    ): String

    /**
     * 引擎必须先由 LlamaEngine 加载好模型（native 侧共用同一份 g_model / g_context）。
     * 所以这里不自己加载，只等 LlamaEngine 报 ready。
     */
    fun ensureLoaded(context: Context): Boolean {
        if (inited) return true
        if (!LlamaEngine.isReady()) {
            lastError = "本地模型未加载"
            return false
        }
        return try {
            AiChat.getInferenceEngine(context)   // 确保 System.loadLibrary("ai-chat") 已执行
            inited = true
            true
        } catch (t: Throwable) {
            lastError = "native 库加载失败: ${t.javaClass.simpleName}: ${t.message}"
            Log.e(TAG, lastError, t)
            false
        }
    }

    /**
     * 整段生成。
     *
     * @param messagesJson OpenAI 消息数组原始 JSON
     * @param toolsJson    OpenAI tools 数组原始 JSON，无工具传 "[]"
     * @return {"content":"...","tool_calls":[...]}；失败时 {"error":{...}}
     */
    fun generate(messagesJson: String, toolsJson: String, nPredict: Int = 1024): String {
        if (!inited) return """{"error":{"message":"${esc(lastError.ifBlank { "引擎未初始化" })}","type":"not_ready"}}"""
        return try {
            // 推理是阻塞的，且 native 侧共用全局 context，必须串行
            synchronized(this) {
                nativeGenerateWithTools(messagesJson, toolsJson, nPredict)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "generate 异常", t)
            """{"error":{"message":"${esc(t.javaClass.simpleName + ": " + (t.message ?: ""))}","type":"inference_error"}}"""
        }
    }

    val error: String get() = lastError

    private fun esc(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")
}
