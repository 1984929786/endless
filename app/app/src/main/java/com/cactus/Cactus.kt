package com.cactus

/**
 * Cactus 引擎的 JNI 声明层。
 *
 * ⚠️ 本文件的签名是**实测**出来的，不是抄文档：
 *    2026-11-20 用 ELF 符号表 + arm64 反汇编直接读 libcactus.so（arm64-v8a, 67.8MB）
 *    的 Java_com_cactus_CactusJNI_* 入口函数，还原每个函数实际怎么用 x0..x7。
 *
 *    ⚠️ 之前的版本声明成 v1.14 签名（nativeInit 第 3 参数是 Boolean、
 *       nativeComplete 返回 String），与实际 .so 完全不符 —— 那是无堆栈 SIGSEGV 的根因：
 *       1) nativeInit 把 Boolean(=1字节) 当 int(4字节) 读 → contextSize 是栈垃圾
 *          → cactus_init 拿到天文数字当上下文长度 → 分配失败/越界 → 崩
 *       2) nativeComplete 参数完全错位：第 3/4 参数是 (ByteArray 输出缓冲, int 缓冲大小)，
 *          我们传的是 (optionsJson, toolsJson) → JNI 层把 jstring 当 jbyteArray 用 → 崩
 *
 *    包名必须是 com.cactus、类名必须是 CactusJNI，
 *    否则 System.loadLibrary 之后找不到 Java_com_cactus_CactusJNI_* 符号。
 */
class CactusJNI {

    companion object {
        init {
            System.loadLibrary("cactus")
        }

        @JvmStatic external fun nativeSetFramework()

        @JvmStatic external fun nativeSetCacheDir(cacheDir: String)

        /**
         * 实测签名：(String modelPath, String corpusDir, int contextSize) -> long
         * 反汇编依据（0x305804 起）：
         *   x2=jstring 判空后 GetStringUTFChars  → modelPath
         *   x3=jstring 判空后 GetStringUTFChars  → corpusDir
         *   w4=x4 直接参与运算(12001ee8: and w8,w23,#0xff; 7100051f: cmp w8,#1)
         *       → 是 int，不是 boolean
         *   最后 cactus_init(path, size, corpus)
         * 返回 0 表示失败。
         */
        @JvmStatic external fun nativeInit(
            modelPath: String,
            corpusDir: String?,
            contextSize: Int
        ): Long

        @JvmStatic external fun nativeDestroy(handle: Long)

        @JvmStatic external fun nativeReset(handle: Long)

        @JvmStatic external fun nativeStop(handle: Long)

        /**
         * 实测签名（反汇编依据 0x305918，916 字节）：
         *   x1=handle(long)
         *   x2=messagesJson(jstring)   → GetStringUTFChars
         *   x3=responseBuffer(jbyteArray) → GetByteArrayElements  ← 输出缓冲！
         *   x4=bufferSize(int)         → movz x3,#65536 说明调用方传 65536 这种大小
         *   x5=optionsJson(jstring?)
         *   x6=toolsJson(jstring?)
         *   x7=callback(jobject?)      → FindClass 后取 invoke 方法
         *   栈上=userData(long)
         * 返回 int：写入缓冲的字节数，<0 表示出错。
         *
         * ⚠️ 与 v1.14 的致命差异：返回的不是 String，模型输出写进 responseBuffer。
         */
        @JvmStatic external fun nativeComplete(
            handle: Long,
            messagesJson: String,
            responseBuffer: ByteArray,
            bufferSize: Int,
            optionsJson: String?,
            toolsJson: String?,
            callback: Any?,
            userData: Long
        ): Int

        @JvmStatic external fun nativeGetLastError(): String
    }
}

/**
 * 逐 token 回调。
 * 实测 .so 里找的是方法名 "onToken"，签名 "(Ljava/lang/String;I)V"：
 *   即 fun onToken(token: String, tokenId: Int)
 * 注意：JNI 侧是 FindClass + GetMethodID("invoke") 拿 Kotlin Function2，
 * 所以传 lambda 也可以，但必须保证整个推理期间强引用住（否则 GC 回收 = 野指针）。
 */
fun interface CactusTokenCallback {
    fun onToken(token: String, tokenId: Int)
}

/** Kotlin 友好的一层包装 */
object CactusEngine {

    /** 实测默认上下文长度。65536 是 .so 内部 nativeComplete 的默认缓冲大小，取它对得上。 */
    private const val DEFAULT_CONTEXT_SIZE = 65536

    @Volatile private var frameworkSet = false

    /**
     * 强引用模型路径。JNI 侧 GetStringUTFChars 之后会 ReleaseStringUTFChars，
     * 理论上安全；但保留这个字段作为双保险，成本为零。
     */
    @Volatile private var heldDir: String? = null

    /** 加载模型目录，返回句柄（0 = 失败） */
    fun init(modelDir: String, contextSize: Int = DEFAULT_CONTEXT_SIZE): Long {
        heldDir = modelDir
        if (!frameworkSet) {
            try { CactusJNI.nativeSetFramework() } catch (_: Throwable) {}
            frameworkSet = true
        }
        return CactusJNI.nativeInit(heldDir!!, null, contextSize)
    }

    fun destroy(handle: Long) = CactusJNI.nativeDestroy(handle)

    fun reset(handle: Long) = CactusJNI.nativeReset(handle)

    fun stop(handle: Long) = CactusJNI.nativeStop(handle)

    fun lastError(): String = try { CactusJNI.nativeGetLastError() } catch (_: Throwable) { "" }

    /**
     * 推理。模型输出写进内部 ByteArray，再转成 String 返回。
     *
     * ⚠️ 旧版本调用的是 CactusEngine.complete(h, messagesJson, optionsJson, null)，
     *    参数个数都不对 —— 那段代码从来没有真正跑通过。
     */
    fun complete(
        handle: Long,
        messagesJson: String,
        optionsJson: String? = null,
        callback: CactusTokenCallback? = null
    ): String? {
        val buf = ByteArray(BUFFER_SIZE)
        val n = CactusJNI.nativeComplete(
            handle, messagesJson, buf, buf.size,
            optionsJson, null, callback, 0L
        )
        if (n < 0) return null
        if (n == 0) return ""
        val len = if (n > buf.size) buf.size else n
        return String(buf, 0, len, Charsets.UTF_8)
    }

    private const val BUFFER_SIZE = 256 * 1024
}
