package com.cactus

/**
 * Cactus 引擎的 JNI 声明层（适配 v1.14 引擎）。
 *
 * ⚠️ 包名必须是 com.cactus、类名必须是 CactusJNI、方法名必须与
 *    libcactus_engine.so 导出的 Java_com_cactus_CactusJNI_nativeXxx 严格一致，
 *    否则 System.loadLibrary 之后找不到符号。
 *
 * v1.14 与 v2.x 的关键差异：
 *   - nativeComplete 返回 String（v2.x 是 jint + ByteArray）
 *   - 无 nativeLogSetLevel
 */
class CactusJNI {

    companion object {
        init {
            System.loadLibrary("cactus")
        }

        @JvmStatic external fun nativeSetFramework()
        @JvmStatic external fun nativeSetCacheDir(cacheDir: String)

        @JvmStatic external fun nativeInit(modelPath: String, corpusDir: String?, cacheIndex: Boolean): Long
        @JvmStatic external fun nativeDestroy(handle: Long)
        @JvmStatic external fun nativeReset(handle: Long)
        @JvmStatic external fun nativeStop(handle: Long)

        @JvmStatic external fun nativeComplete(
            handle: Long,
            messagesJson: String,
            optionsJson: String?,
            toolsJson: String?,
            callback: CactusTokenCallback?,
            pcmData: ByteArray?
        ): String?

        @JvmStatic external fun nativeGetLastError(): String
    }
}

/** 逐 token 回调，v1.14 引擎原生支持流式 */
fun interface CactusTokenCallback {
    fun onToken(token: String, tokenId: Int)
}

/** Kotlin 友好的一层包装 */
object CactusEngine {

    /** 加载模型目录，返回句柄（0 = 失败） */
    fun init(modelDir: String): Long = CactusJNI.nativeInit(modelDir, null, false)

    fun destroy(handle: Long) = CactusJNI.nativeDestroy(handle)

    /**
     * 同步对话。返回 Cactus 原始 JSON 字符串，失败返回 null。
     * messagesJson 是 OpenAI 格式的 message 数组。
     */
    fun complete(handle: Long, messagesJson: String, optionsJson: String?, toolsJson: String?): String? =
        CactusJNI.nativeComplete(handle, messagesJson, optionsJson, toolsJson, null, null)

    /** 流式对话，每个 token 回调一次 */
    fun completeStream(
        handle: Long,
        messagesJson: String,
        optionsJson: String?,
        toolsJson: String?,
        onToken: (String, Int) -> Unit
    ): String? = CactusJNI.nativeComplete(
        handle, messagesJson, optionsJson, toolsJson,
        CactusTokenCallback { tok, id -> onToken(tok, id) }, null
    )

    fun lastError(): String = try { CactusJNI.nativeGetLastError() } catch (t: Throwable) { "" }
}
