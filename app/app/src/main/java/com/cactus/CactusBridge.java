package com.cactus

/**
 * Cactus 引擎的 JNI 声明层（纯 Java，不用 Kotlin 的 expect/actual 多平台机制）。
 *
 * ⚠️ 包名必须是 com.cactus、类名必须是 CactusJNI、方法名必须与
 *    libcactus_engine.so 导出的 Java_com_cactus_CactusJNI_nativeXxx 一致。
 *    这个文件是从 Cactus 官方 bindings/kotlin 移植的，只保留我们首版用得上的方法。
 */
public class CactusJNI {

    static {
        System.loadLibrary("cactus_engine");
    }

    public static native long nativeInit(String modelPath, String corpusDir, boolean cacheIndex);
    public static native void nativeDestroy(long handle);
    public static native void nativeReset(long handle);
    public static native void nativeStop(long handle);

    public static native int nativeComplete(
            long handle,
            String messagesJson,
            byte[] responseBuffer,
            String optionsJson,
            String toolsJson,
            CactusTokenCallback callback,
            byte[] pcmData);

    public static native int nativePrefill(
            long handle,
            String messagesJson,
            byte[] responseBuffer,
            String optionsJson,
            String toolsJson,
            byte[] pcmData);

    public static native String nativeGetLastError();
    public static native void nativeLogSetLevel(int level);
    public static native void nativeSetAppId(String appId);
}

/** 逐 token 回调（首版不用流式，保留给后续） */
public interface CactusTokenCallback {
    void onToken(String token, int tokenId);
}

/** Kotlin 友好的一层包装 */
public final class CactusEngine {

    private CactusEngine() {}

    /** 加载模型目录，返回句柄（0 = 失败） */
    public static long init(String modelDir) {
        return CactusJNI.nativeInit(modelDir, null, false);
    }

    public static void destroy(long h) {
        CactusJNI.nativeDestroy(h);
    }

    /**
     * 同步对话。返回 Cactus 原始 JSON 字符串，失败返回 null。
     * messagesJson 是 OpenAI 格式的 message 数组。
     */
    public static String complete(long h, String messagesJson, String optionsJson, String toolsJson) {
        byte[] buf = new byte[4 * 1024 * 1024];
        int rc = CactusJNI.nativeComplete(h, messagesJson, buf, optionsJson, toolsJson, null, null);
        if (rc < 0) return null;
        int len = 0;
        while (len < buf.length && buf[len] != 0) len++;
        try {
            return new String(buf, 0, len, "UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    public static String lastError() {
        String s = CactusJNI.nativeGetLastError();
        return s == null ? "" : s;
    }
}
