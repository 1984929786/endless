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
     * 阻塞式启动：确保模型真正就绪了 JS 才进入主界面。
     * 最长等 180 秒（首启解压需要时间），超时就报错而不是卡死。
     */
    @JavascriptInterface
    fun bootstrap(): String {
        if (bridge.modelReady) return "ready"

        val done = CountDownLatch(1)
        var result = "ok"

        loadModelInternal { ok, msg ->
            result = if (ok) "ok" else "fail:$msg"
            done.countDown()
        }

        val ok = done.await(180, TimeUnit.SECONDS)
        if (!ok) return "fail:模型加载超时"
        return result
    }

    @JavascriptInterface
    fun loadModel(): String {
        val done = CountDownLatch(1)
        var result = "ok"

        loadModelInternal { ok, msg ->
            result = if (ok) "ok" else "fail:$msg"
            done.countDown()
        }

        if (!done.await(180, TimeUnit.SECONDS)) return "fail:模型加载超时"
        return result
    }

    private fun loadModelInternal(cb: (Boolean, String) -> Unit) {
        // 防重入：已经在加载就直接返回
        if (!loading.compareAndSet(false, true)) {
            cb(false, "正在加载中")
            return
        }
        try {
            bridge.loadModelAsync(
                onProgress = { msg -> Log.i(TAG, msg) },
                onDone = { ok, msg ->
                    loading.set(false)
                    cb(ok, msg)
                }
            )
        } catch (t: Throwable) {
            loading.set(false)
            Log.e(TAG, "loadModel throw", t)
            cb(false, t.message ?: "未知错误")
        }
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
