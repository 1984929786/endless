package com.cactus

/**
 * Cactus 引擎的 JNI 声明层。
 *
 * ⚠️ 包名必须是 com.cactus、类名必须是 CactusJNI、方法名必须与
 *    libcactus_engine.so 导出的 Java_com_cactus_CactusJNI_nativeXxx 严格一致，
 *    否则 System.loadLibrary 之后找不到符号。
 */
class CactusJNI {

    companion object {
        init {
            System.loadLibrary("cactus_engine")
        }

        @JvmStatic external fun nativeInit(modelPath: String, corpusDir: String?, cacheIndex: Boolean): Long
        @JvmStatic external fun nativeDestroy(handle: Long)
        @JvmStatic external fun nativeReset(handle: Long)
        @JvmStatic external fun nativeStop(handle: Long)

        @JvmStatic external fun nativeComplete(
            handle: Long,
            messagesJson: String,
            responseBuffer: ByteArray,
            optionsJson: String?,
            toolsJson: String?,
            callback: CactusTokenCallback?,
            pcmData: ByteArray?
        ): Int

        @JvmStatic external fun nativeGetLastError(): String
        @JvmStatic external fun nativeLogSetLevel(level: Int)
    }
}

/** 逐 token 回调（首版不用流式，保留给后续） */
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
    fun complete(handle: Long, messagesJson: String, optionsJson: String?, toolsJson: String?): String? {
        val buf = ByteArray(4 * 1024 * 1024)
        val rc = CactusJNI.nativeComplete(handle, messagesJson, buf, optionsJson, toolsJson, null, null)
        if (rc < 0) return null
        var len = 0
        while (len < buf.size && buf[len] != 0.toByte()) len++
        return String(buf, 0, len, Charsets.UTF_8)
    }

    fun lastError(): String = try { CactusJNI.nativeGetLastError() } catch (t: Throwable) { "" }
}
