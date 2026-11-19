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

    /** JS 轮询：返回 ready / loading:进度描述 / fail:原因 */
    @JavascriptInterface
    fun status(): String {
        if (bridge.modelReady) return "ready"
        val err = lastLoadError
        if (err != null) return "fail:$err"
        if (loading.get()) return "loading:" + (lastProgress ?: "正在加载模型…")
        return "idle"
    }

    @Volatile private var lastProgress: String? = null
    @Volatile private var lastLoadError: String? = null

    private fun startLoadAsync() {
        if (!loading.compareAndSet(false, true)) return
        lastLoadError = null
        lastProgress = "正在释放内置模型…"
        Thread {
            try {
                bridge.loadModelAsync(
                    onProgress = { msg ->
                        lastProgress = msg
                        Log.i(TAG, msg)
                    },
                    onDone = { ok, msg ->
                        loading.set(false)
                        if (ok) {
                            lastProgress = "就绪"
                            lastLoadError = null
                        } else {
                            lastLoadError = msg
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
     * 流式推理。JS 侧只拿最终完整文本；这里用内部回调拼。
     * 单独线程 + 超时，绝不阻塞 UI。
     */
    @JavascriptInterface
    fun complete(payloadJson: String): String {
        if (!bridge.modelReady) return ""
        if (!busy.compareAndSet(false, true)) return ""

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
                .put("max_tokens", 512)
                .put("temperature", 0.7)
                .put("auto_handoff", false)

            var out: String? = null
            val t = Thread {
                out = try {
                    CactusEngineHolder.complete(msgs.toString(), opts.toString())
                } catch (th: Throwable) {
                    Log.e(TAG, "complete failed", th)
                    null
                }
            }
            t.start()
            t.join(120_000)

            if (t.isAlive) {
                Log.w(TAG, "推理超时")
                try { CactusEngineHolder.stop() } catch (_: Throwable) {}
                return ""
            }

            val raw = out ?: return ""
            return extractText(raw)
        } catch (t: Throwable) {
            Log.e(TAG, "complete throw", t)
            return ""
        } finally {
            busy.set(false)
        }
    }

    /** 从引擎返回的 JSON 里取正文；不是 JSON 就原样返回 */
    private fun extractText(raw: String): String {
        val s = raw.trim()
        if (!s.startsWith("{")) return s
        return try {
            val o = JSONObject(s)
            when {
                o.has("response") -> o.optString("response")
                o.has("text") -> o.optString("text")
                o.has("content") -> o.optString("content")
                else -> s
            }
        } catch (t: Throwable) {
            s
        }
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
