package com.xundao.yongwuzhijing

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 永无止境的「手」——OpenClaw 网关客户端。
 *
 * 走网关原生的 OpenAI 兼容端点 POST /v1/chat/completions：
 * 背后是完整 agent 运行路径（文件/命令/浏览器/内存/定时全可用）。
 */
object AgentClient {

    private const val TAG = "AgentClient"
    private const val HOST = "127.0.0.1"
    val PORTS = intArrayOf(GatewayLauncher.PORT)

    @Volatile private var activePort = -1
    @Volatile private var token: String = GatewayLauncher.TOKEN
    @Volatile var enabled: Boolean = true

    @Volatile private var lastProbeAt = 0L
    @Volatile private var lastProbeOk = false
    @Volatile var lastProbeError: String = ""
    @Volatile var lastChatNote: String = ""

    data class Result(val ok: Boolean, val text: String, val error: String = "")

    fun lastProbeOk(): Boolean = lastProbeOk

    // ---- 探活 ----

    fun probe(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastProbeAt < 15_000) return lastProbeOk
        lastProbeAt = now
        lastProbeError = ""
        val ok = try {
            val p = findPort()
            if (p < 0) {
                lastProbeError = "所有候选端口都不通 " + PORTS.joinToString(",")
                false
            } else true
        } catch (t: Throwable) {
            lastProbeError = "${t.javaClass.simpleName}: ${t.message}"
            Log.i(TAG, "网关不可达: $lastProbeError")
            false
        }
        lastProbeOk = ok
        return ok
    }

    fun healthLine(): String {
        val port = if (activePort > 0) activePort else PORTS[0]
        val t0 = System.currentTimeMillis()
        return try {
            val c = URL("http://$HOST:$port/health").openConnection() as HttpURLConnection
            c.requestMethod = "GET"
            c.connectTimeout = 3000
            c.readTimeout = 3000
            c.setRequestProperty("Authorization", "Bearer $token")
            val code = c.responseCode
            c.disconnect()
            val ms = System.currentTimeMillis() - t0
            when (code) {
                200 -> "200 正常(${ms}ms)"
                401, 403 -> "$code token 不对(${ms}ms)"
                404 -> "404 不是网关的路由"
                else -> "$code(${ms}ms)"
            }
        } catch (t: Throwable) {
            "${t.javaClass.simpleName}(${System.currentTimeMillis() - t0}ms)"
        }
    }

    fun selfTest(): String {
        val sb = StringBuilder()
        for (p in PORTS) {
            sb.append(p).append(':').append(httpCheck(p)).append(' ')
        }
        if (activePort > 0) sb.append("active=").append(activePort)
        return sb.toString().trim()
    }

    private fun httpCheck(port: Int): String {
        val t0 = System.currentTimeMillis()
        return try {
            java.net.Socket().use { s ->
                s.connect(java.net.InetSocketAddress(HOST, port), 2000)
                s.soTimeout = 2500
                val req = "GET /health HTTP/1.1\r\nHost: $HOST:$port\r\n" +
                        "Authorization: Bearer $token\r\nConnection: close\r\n\r\n"
                s.getOutputStream().apply { write(req.toByteArray()); flush() }
                val buf = ByteArray(256)
                val n = s.getInputStream().read(buf)
                val ms = System.currentTimeMillis() - t0
                if (n <= 0) "empty(${ms}ms)" else {
                    val line = String(buf, 0, n, Charsets.ISO_8859_1).lineSequence().firstOrNull() ?: ""
                    val code = line.split(' ').getOrNull(1) ?: line.take(12)
                    "$code(${ms}ms)"
                }
            }
        } catch (t: Throwable) {
            "${t.javaClass.simpleName}(${System.currentTimeMillis() - t0}ms)"
        }
    }

    fun resetProbe() {
        lastProbeAt = 0L
        lastProbeOk = false
        lastProbeError = ""
        lastChatNote = ""
        activePort = -1
    }

    // ---- 对话 ----

    fun chat(messages: List<Triple<String, String, String>>, sessionKey: String = "ywzj-app"): Result {
        if (!enabled) return Result(false, "", "Agent 已关闭")
        if (!probe()) return Result(false, "", "网关未运行，已切本地模型")

        return try {
            val arr = JSONArray()
            messages.forEach { (role, content, _) ->
                arr.put(JSONObject().put("role", role).put("content", content))
            }
            val body = JSONObject()
                .put("model", "openclaw")
                .put("messages", arr)
                .put("user", sessionKey)
                .put("stream", false)
                .toString()

            lastChatNote = "POST /v1/chat/completions port=" + (if (activePort > 0) activePort else PORTS[0]) +
                    " key=" + token.take(6)

            val c = open("/v1/chat/completions", "POST")
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("x-openclaw-session-key", sessionKey)
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = c.responseCode
            val respText = readAll(if (code in 200..299) c.inputStream else c.errorStream)
            c.disconnect()

            if (code !in 200..299) {
                Log.w(TAG, "网关返回 $code")
                lastChatNote = "HTTP $code: " + respText.take(160)
                return Result(false, "", "网关返回 $code")
            }

            val obj = JSONObject(respText)
            val choices = obj.optJSONArray("choices")
                ?: return Result(false, "", "响应缺少 choices")

            if (choices.length() == 0) return Result(false, "", "响应 choices 为空")

            val msg = choices.getJSONObject(0).optJSONObject("message")
            val content = msg?.optString("content").orEmpty()
            if (content.isBlank()) return Result(false, "", "网关返回空内容")

            lastChatNote = "OK ${content.length}字"
            Result(true, content)
        } catch (t: Throwable) {
            Log.e(TAG, "调用失败", t)
            lastChatNote = "${t.javaClass.simpleName}: ${t.message}".take(160)
            Result(false, "", t.message ?: "网络异常")
        }
    }

    // ---- Token ----

    fun loadToken(configFile: java.io.File): Boolean = try {
        if (!configFile.exists()) return false
        val json = JSONObject(configFile.readText())
        val t = json.optJSONObject("gateway")
            ?.optJSONObject("auth")
            ?.optString("token", "") ?: ""
        if (t.isNotBlank()) { token = t; true } else { token = GatewayLauncher.TOKEN; false }
    } catch (e: Throwable) {
        Log.i(TAG, "读 token 失败: ${e.message}")
        false
    }

    // ---- 底层 ----

    private fun open(path: String, method: String): HttpURLConnection {
        val port = if (activePort > 0) activePort else PORTS[0]
        val c = URL("http://$HOST:$port$path").openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 8000
        c.readTimeout = 180_000
        c.setRequestProperty("Authorization", "Bearer $token")
        return c
    }

    private fun findPort(): Int {
        for (p in PORTS) {
            try {
                val c = URL("http://$HOST:$p/health").openConnection() as HttpURLConnection
                c.requestMethod = "GET"
                c.connectTimeout = 4000
                c.readTimeout = 4000
                c.setRequestProperty("Authorization", "Bearer $token")
                val code = c.responseCode
                c.disconnect()
                if (code == 200) { activePort = p; return p }
            } catch (_: Throwable) {}
        }
        return -1
    }

    private fun readAll(s: java.io.InputStream?): String {
        if (s == null) return ""
        return s.use { input ->
            val bos = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                bos.write(buf, 0, n)
            }
            bos.toString("UTF-8")
        }
    }
}
