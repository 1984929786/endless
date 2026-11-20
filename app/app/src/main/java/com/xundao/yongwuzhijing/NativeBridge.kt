package com.xundao.yongwuzhijing

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 给 WebView 的 JS 桥。
 * 所有可能崩溃的 native 调用都装在这里，Kotlin 异常不往外抛，native crash 靠状态锁避免。
 */
class NativeBridge(
    private val context: Context,
    private val bridge: ModelBridge
) {
    companion object {
        private const val TAG = "NativeBridge"
        private val MAIN = Handler(Looper.getMainLooper())
    }

    private val loading = AtomicBoolean(false)
    private val busy = AtomicBoolean(false)
    private val sessionsDir = File(context.filesDir, "sessions").apply { mkdirs() }

    // ---------------- 启动流程 ----------------

  /**
     * 启动：立即返回，后台异步加载模型。JS 轮询 status() 拿进度。
     * 不能阻塞 JS 线程，否则界面卡死。
     */
    @JavascriptInterface
    fun bootstrap(): String {
        if (bridge.modelReady) return "ready"
        if (loading.get()) return "loading"
        startLoadAsync()
        return "loading"
    }

    /** JS 轮询：返回 ready / loading:进度 / fail:原因 / staged:模型文件已就绪待加载 / idle */
    @JavascriptInterface
    fun status(): String {
        if (bridge.modelReady) return "ready"
        val err = lastLoadError
        if (err != null) return "fail:$err"
        if (loading.get()) return "loading:" + (lastProgress ?: "正在加载模型…")
        // 模型文件已经释放到私有目录了 —— 不是「还要释放」，只是需要加载进内存。
        // 这个状态要持久化判断（看文件，不看进程内变量），冷启动也能认出来。
        if (bridge.hasStagedModel()) return "staged"
        return "idle"
    }

    /** 读崩溃日志（给用户看 / 发给我） */
    @JavascriptInterface
    fun crashLog(): String = CrashCatcher.read(context)

    @JavascriptInterface
    fun lastStage(): String = CrashCatcher.lastStageText(context)

    /** 内存状况，排查 OOM */
    @JavascriptInterface
    fun memInfo(): String {
        return try {
            val act = Runtime.getRuntime()
            val sb = StringBuilder()
            sb.append("heap").append(" ").append((act.totalMemory() - act.freeMemory()) / 1048576).append("MB/")
                .append(act.maxMemory() / 1048576).append("MB")
            val am = context.getSystemService(android.content.Context.ACTIVITY_SERVICE)
                    as android.app.ActivityManager
            val mi = android.app.ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            sb.append(" | 系统可用 ").append(mi.availMem / 1048576).append("MB")
            sb.append(" | 低内存=").append(mi.lowMemory)
            sb.toString()
        } catch (t: Throwable) {
            "未知"
        }
    }

    /** 把崩溃日志导出到应用外部可读的地方 */
    @JavascriptInterface
    fun exportCrash(): String {
        return try {
            val txt = "stage: " + CrashCatcher.lastStageText(context) + "\n\n" + CrashCatcher.read(context)
            if (txt.isBlank()) return "没有崩溃记录"
            val f = File(context.getExternalFilesDir(null), "crash-${System.currentTimeMillis()}.txt")
            f.writeText(txt)
            f.absolutePath
        } catch (t: Throwable) {
            "导出失败: ${t.message}"
        }
    }

    @Volatile private var lastProgress: String? = null
    @Volatile private var lastLoadError: String? = null

    /**
     * 整个进程只允许调用一次 nativeInit。
     *
     * 根因（官方 issue #130，维护者原话）：
     *   "stale per-prefix engine state" —— v1.14 引擎在重复 init 时会恢复
     *   陈旧的 KV/decode 状态，没恢复全 → SIGSEGV（无 Java 堆栈）。
     * 修复只在 main 分支（v4.x），v1.14 没有。
     *
     * 所以：init 只能成功一次。失败也不再重试（重试 = 第二次 init = 必崩），
     * 而是告诉用户重启应用。
     */
    @Volatile private var initAttempted = false

    private fun startLoadAsync() {
        if (initAttempted) {
            // 已经试过了。若模型已就绪就正常；否则明确告知必须重启
            if (bridge.modelReady) {
                lastProgress = "就绪"
                lastLoadError = null
            } else {
                lastLoadError = "引擎初始化失败，请完全退出应用（从最近任务划掉）后重新打开"
            }
            return
        }
        if (!loading.compareAndSet(false, true)) return
        initAttempted = true
        lastLoadError = null
        lastProgress = "正在释放内置模型…"
        CrashCatcher.stage("开始异步加载模型")
        Thread {
            try {
                bridge.loadModelAsync(
                    onProgress = { msg ->
                        lastProgress = msg
                        CrashCatcher.stage(msg)
                        Log.i(TAG, msg)
                    },
                    onDone = { ok, msg ->
                        loading.set(false)
                        if (ok) {
                            lastProgress = "就绪"
                            lastLoadError = null
                            CrashCatcher.stage("模型加载成功")
                        } else {
                            lastLoadError = msg
                            CrashCatcher.stage("模型加载失败: $msg")
                        }
                    }
                )
            } catch (t: Throwable) {
                loading.set(false)
                lastLoadError = t.message ?: "未知错误"
                Log.e(TAG, "loadModel throw", t)
            }
        }.start()
    }

    @JavascriptInterface
    fun loadModel(): String {
        startLoadAsync()
        return status()
    }

    // ---------------- 推理 ----------------

    /**
     * 推理。同步返回结果字符串，JS 在独立线程调用。
     * 不能用「JS 线程 join 后台线程」——JS 线程持着锁 join 会造成 stalling。
     * 直接在调用线程里跑 native（WebView 的 JS 调用本来就跑在非 UI 线程）。
     */
    @JavascriptInterface
    fun complete(payloadJson: String): String {
        if (!bridge.modelReady) return ""
        if (!busy.compareAndSet(false, true)) return ""

        CrashCatcher.stage("推理开始")
        try {
            val payload = JSONObject(payloadJson)
            val arr = payload.optJSONArray("messages") ?: JSONArray()
            val msgs = JSONArray()
            for (i in 0 until arr.length()) {
                val m = arr.getJSONObject(i)
                msgs.put(
                    JSONObject()
                        .put("role", m.optString("role", "user"))
                        .put("content", m.optString("content", ""))
                )
            }

            val opts = JSONObject()
                .put("max_tokens", 256)
                .put("temperature", 0.7)
                .put("auto_handoff", false)

            val raw = try {
                // llama.cpp 走纯文本，把 messages 拍成 prompt
                var sys: String? = null
                val pb = StringBuilder()
                for (i in 0 until msgs.length()) {
                    val m = msgs.getJSONObject(i)
                    val role = m.optString("role", "user")
                    val content = m.optString("content", "")
                    when (role) {
                        "system" -> sys = if (sys.isNullOrBlank()) content else "$sys\n$content"
                        "assistant" -> pb.append("助手：").append(content).append("\n")
                        else -> pb.append("用户：").append(content).append("\n")
                    }
                }
                // Qwen3 默认会输出 <think>…</think> 内部推理，白占 token 还把正文截断。
                // 官方开关：用户消息末尾加 /no_think。
                LlamaEngine.complete(pb.toString().trim() + "\n/no_think", sys)
            } catch (th: Throwable) {
                Log.e(TAG, "complete failed", th)
                CrashCatcher.stage("推理异常: ${th.message}")
                null
            }

            CrashCatcher.stage("推理完成")
            if (raw == null) return ""
            return extractText(raw)
        } catch (t: Throwable) {
            Log.e(TAG, "complete throw", t)
            CrashCatcher.stage("推理失败: ${t.message}")
            return ""
        } finally {
            busy.set(false)
        }
    }

    /**
     * 从引擎返回里取正文。
     *
     * Qwen3 是 thinking 模型，输出形如：
     *   <think>…一大段内部推理…</think>\n\n真正的回复
     * 思考过程绝不能展示给用户（道友 2026-11-21 截图反馈）。
     * 另外当生成被 max_tokens 截断时，</think> 可能根本还没出现，
     * 这时整段都是思考内容，正文为空 —— 宁可返回提示也不把思考当回复。
     */
    private fun extractText(raw: String): String {
        var s = raw.trim()
        // 不是 JSON 就直接当文本
        if (s.startsWith("{")) {
            s = try {
                val o = JSONObject(s)
                when {
                    o.has("response") -> o.optString("response")
                    o.has("text") -> o.optString("text")
                    o.has("content") -> o.optString("content")
                    else -> s
                }
            } catch (t: Throwable) { s }
        }
        return stripThinking(s)
    }

    /** 剥掉 <think> 段。未闭合的思考段说明生成被截断，整段丢弃。 */
    private fun stripThinking(input: String): String {
        var s = input
        // 1. 完整的 <think>…</think>（含变体标签）全部删掉
        val closed = Regex("<\\s*(think|thinking|reasoning)\\s*>[\\s\\S]*?<\\s*/\\s*(think|thinking|reasoning)\\s*>",
                RegexOption.IGNORE_CASE)
        s = closed.replace(s, "")

        // 2. 还有未闭合的 <think> 开头 —— 生成被截断，思考内容不完整
        val openTag = Regex("<\\s*(think|thinking|reasoning)\\s*>", RegexOption.IGNORE_CASE)
        if (openTag.containsMatchIn(s)) {
            // 思考都没写完，正文一个也没有
            return ""
        }

        // 3. 残留的孤立闭合标签也去掉
        s = Regex("<\\s*/\\s*(think|thinking|reasoning)\\s*>", RegexOption.IGNORE_CASE).replace(s, "")

        return s.trim()
    }

    // ---------------- 会话存储 ----------------

    @JavascriptInterface
    fun saveSession(id: String, json: String): String {
        return try {
            val f = File(sessionsDir, safeName(id) + ".json")
            f.writeText(json)
            "ok"
        } catch (t: Throwable) {
            Log.e(TAG, "saveSession", t)
            "fail"
        }
    }

    @JavascriptInterface
    fun listSessions(): String {
        return try {
            val files = sessionsDir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: emptyArray()
            val arr = JSONArray()
            for (f in files) {
                try {
                    arr.put(JSONObject(f.readText()))
                } catch (_: Throwable) {}
            }
            arr.toString()
        } catch (t: Throwable) {
            Log.e(TAG, "listSessions", t)
            "[]"
        }
    }

    @JavascriptInterface
    fun clearSessions(): String {
        return try {
            sessionsDir.listFiles()?.forEach { it.delete() }
            "ok"
        } catch (t: Throwable) {
            "fail"
        }
    }

    // ---------------- 其他 ----------------

    @JavascriptInterface
    fun modelInfo(): String {
        return try {
            JSONObject()
                .put("name", "Qwen3-0.6B")
                .put("path", bridge.currentModelPath())
                .put("ready", bridge.modelReady)
                .toString()
        } catch (t: Throwable) {
            "{}"
        }
    }

    private fun safeName(s: String): String =
        s.replace(Regex("[^A-Za-z0-9_-]"), "_").take(60).ifEmpty { "s" }
}
