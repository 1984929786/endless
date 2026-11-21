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
 * 设计依据（`永无止境-开发规格书.md`）：本地模型是脑，OpenClaw 是手。
 *
 * 走的是网关原生的 OpenAI 兼容端点 `POST /v1/chat/completions`：
 * 背后是完整 agent 运行路径，文件/命令/浏览器/定时全可用。
 * 走通实测：让它执行 `uname -a`，返回真实内核串。
 *
 * 为什么不走 `/tools/invoke`（查证过，2026-11-21）：
 * 该端点默认 deny 掉 exec / shell / fs_write / fs_delete / fs_move，
 * 恰好是规格书要的三样里最难的两样，等于残缺。而 chat/completions
 * 走 agent 路径，不受这份 HTTP 硬黑名单限制。
 *
 * 【2026-11-21 B 方案重写】
 * 网关现在是**内嵌在本 App 里**的（见 GatewayLauncher），不再是另一个包。
 * 所以：
 *   · 不需要转发层（旧的 18795 已废弃）
 *   · 不需要探另一个 App 的回环端口（那个跨进程超时问题根本不存在了）
 *   · 端口固定 18800，就是本进程拉起的那个网关
 * 安全边界：仍然只连 127.0.0.1。
 */
object AgentClient {

    private const val TAG = "AgentClient"
    private const val HOST = "127.0.0.1"

    /**
     * 真实探一次 /health，返回可读结果（给「网关」页用）。
     * 200 = 通了且 token 对；401 = 端口在但钥匙错；其它 = 不是网关。
     */
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
                401, 403 -> "${code} token 不对(${ms}ms)"
                404 -> "404 不是网关的路由"
                else -> "$code(${ms}ms)"
            }
        } catch (t: Throwable) {
            t.javaClass.simpleName + "(${System.currentTimeMillis() - t0}ms)"
        }
    }

    /**
     * 内嵌网关端口。
     *
     * 【绝不能写 18790】—— 那是 App 自己 ModelBridge 的端口（本地模型 HTTP 服务）。
     * 2026-11-21 的坑：曾把转发口设为 18790，Socket connect 秒通、却永远拿不到
     * /health 200，因为连到的是自己的模型服务，它没有 /health 路由。白费一版。
     *
     * 【也不能是 18789】—— 那是「寻道」（com.xundao.openclaw）的主控制通道。
     * B 方案下两个网关完全隔离，各用各的端口和数据目录。
     */
    val PORTS = intArrayOf(GatewayLauncher.PORT)

    /**
     * 上一次探活成功的端口，-1 表示还没有。
     *
     * 2026-11-21 的坑：probe() 只改局部变量、从不写 activePort，结果 chat() 永远用
     * PORTS[0]=18800 去发（恰好对）。一旦 PORT 变了这里就会错位，所以探活成功必须回填。
     */
    @Volatile private var activePort = -1

    /** 网关 token。
     *
     * 内嵌网关首次启动会自己生成一个写进 OPENCLAW_CONFIG_PATH，
     * 所以这个默认值只在「连外部网关」时才用得上。
     * B 方案下真实 token 应该在启动后从 config 里读出来（见 GatewayLauncher）。
     *
     * 2026-11-21：从空串改成 GatewayLauncher.TOKEN。原因：旧包生成的配置是空的，
     * loadToken() 读不到任何东西，于是每句话都带着空 key 请求 → 网关 401 →
     * 界面报「网关未运行，已切本地模型」，看起来像连不上，其实是没钥匙。
     */
    @Volatile var token: String = GatewayLauncher.TOKEN

    @Volatile var enabled: Boolean = true

    /** 连通性探测结果缓存，避免每句话都探一次 */
    @Volatile private var lastProbeAt = 0L
    @Volatile private var lastProbeOk = false

    data class Result(
        val ok: Boolean,
        val text: String,
        val error: String = ""
    )

    /** 探活失败的具体原因（空 = 没失败过）。写进 last_stage.txt 用于装机排障。 */
    @Volatile var lastProbeError: String = ""

    /** 最近一次 chat 的结果摘要。装机上翻一次就知道卡在哪一环。 */
    @Volatile var lastChatNote: String = ""

    /** 读上次探活缓存值（不发网络请求）。给 diag 用。 */
    fun lastProbeOk(): Boolean = lastProbeOk

    /**
     * 裸 Socket 连通性自检（不经过 HttpURLConnection/代理/连接池）。
     *
     * 2026-11-21 为什么要这个：App 报 SocketTimeoutException，而同一台机器的
     * node 进程连 127.0.0.1:18789 秒通。用 HttpURLConnection 分不清是「SYN 无应答」
     * 还是「连上了但没回数据」。裸 Socket 能测出毫秒级真实结果，而且能分别测
     * IPv4 / IPv6 / localhost 三种地址，直接看出是哪一层被拦。
     */
    fun selfTest(): String {
        val sb = StringBuilder()
        // 只测 ipv4 + 两个端口：6 组合太多，而且 ipv6/localhost 已经知道都能连
        for (p in PORTS) {
            sb.append(p).append(':').append(httpCheck(p)).append(' ')
        }
        if (activePort > 0) sb.append("active=").append(activePort)
        return sb.toString().trim()
    }

    /**
     * 真的发一个 GET /health 并读回响应，返回简短结果。
     *
     * 为什么不只用 connect()：2026-11-21 的教训 —— 裸 Socket connect 成功
     * 只证明「有东西在监听这个端口」，不证明它是网关。
     * App 自己的 ModelBridge 就占着 18790，connect 秒通，但 /health 不是网关的路由，
     * 永远拿不到 200。必须看真实响应才能分辨。
     */
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
                    // 只要状态码，不要理由短语（中文撑爆界面）
                    val code = line.split(' ').getOrNull(1) ?: line.take(12)
                    "$code(${ms}ms)"
                }
            }
        } catch (t: Throwable) {
            t.javaClass.simpleName + "(${System.currentTimeMillis() - t0}ms)"
        }
    }

    /** 清空探活缓存，下一句话强制重新探测（诊断用）。 */
    fun resetProbe() {
        lastProbeAt = 0L
        lastProbeOk = false
        lastProbeError = ""
        lastChatNote = ""
        activePort = -1
    }

    /** 快速探活：网关在不在。3 秒超时，失败不打日志噪音。 */
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
            lastProbeError = (t.javaClass.simpleName + ": " + (t.message ?: "")) +
                    " (host=$HOST port=" + PORTS.joinToString(",") + ")"
            Log.i(TAG, "网关不可达: $lastProbeError")
            false
        }
        lastProbeOk = ok
        return ok
    }

    /**
     * 从内嵌网关的配置文件里读 token。
     *
     * 为什么要读：GatewayLauncher 首次启动时，若配置里没有 gateway.auth.token，
     * 网关会自己生成一个并写回配置。所以硬编码 token 对内嵌网关是无效的，
     * 必须启动后从文件里读。
     *
     * @return 读到返回 true 并设置 token
     */
    fun loadToken(configFile: java.io.File): Boolean {
        return try {
            if (!configFile.exists()) return false
            val json = JSONObject(configFile.readText())
            val t = json.optJSONObject("gateway")
                ?.optJSONObject("auth")
                ?.optString("token", "")
                ?: ""
            if (t.isNotEmpty()) {
                token = t
                true
            } else {
                // 配置里没 token 不代表不能连：内嵌网关跑的就是我们写死的那把钥匙
                token = GatewayLauncher.TOKEN
                false
            }
        } catch (e: Throwable) {
            Log.i(TAG, "读 token 失败: ${e.message}")
            false
        }
    }

    /**
     * 发一轮对话给 OpenClaw。
     *
     * @param messages 完整的消息数组（含历史），OpenClaw 侧按 user 字段维持会话
     * @param sessionKey 稳定的会话标识，同一串消息共享上下文
     */
    fun chat(messages: List<Triple<String, String, String>>, sessionKey: String = "ywzj-app"): Result {
        if (!enabled) {
            lastChatNote = "Agent 已关闭"
            return Result(false, "", "Agent 已关闭")
        }
        if (!probe()) {
            lastChatNote = "探活失败: " + lastProbeError
            return Result(false, "", "网关未运行，已切本地模型")
        }

        return try {
            val arr = JSONArray()
            messages.forEach { (role, content, _) ->
                arr.put(JSONObject().put("role", role).put("content", content))
            }
            val body = JSONObject()
                .put("model", "openclaw")
                .put("messages", arr)
                .put("user", sessionKey)          // 稳定 sessionKey → 共享上下文
                .put("stream", false)
                .toString()

            // 2026-11-21：user 字段只决定会话归属，网关还会按 channel 再分一层，
            // 有时候落到不同会话里就变成「忘了刚才说什么」。显式给 session-key 头最稳。
            lastChatNote = "POST /v1/chat/completions port=" + (if (activePort > 0) activePort else PORTS[0]) +
                    " key=" + token.take(6)

            val c = open("/v1/chat/completions", "POST")
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("x-openclaw-session-key", sessionKey)
            val out = c.outputStream
            out.write(body.toByteArray(Charsets.UTF_8))
            out.flush()
            out.close()

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
                ?: run {
                    lastChatNote = "响应缺 choices: " + respText.take(120)
                    return Result(false, "", "响应缺少 choices")
                }
            if (choices.length() == 0) {
                lastChatNote = "choices 为空"
                return Result(false, "", "响应 choices 为空")
            }

            val msg = choices.getJSONObject(0).optJSONObject("message")
            val content = msg?.optString("content").orEmpty()
            if (content.isBlank()) {
                lastChatNote = "空内容: " + respText.take(160)
                return Result(false, "", "网关返回空内容")
            }
            lastChatNote = "OK ${content.length}字"
            Result(true, content)
        } catch (t: Throwable) {
            Log.e(TAG, "调用失败", t)
            lastChatNote = (t.javaClass.simpleName + ": " + (t.message ?: "")).take(160)
            Result(false, "", t.message ?: "网络异常")
        }
    }

    private fun open(path: String, method: String): HttpURLConnection {
        val port = if (activePort > 0) activePort else PORTS[0]        val c = URL("http://$HOST:$port$path").openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 8000          // 冷启动/模型加载中 CPU 吃满时，3 秒不够
        c.readTimeout = 180_000          // agent 可能跑工具，给足时间
        c.setRequestProperty("Authorization", "Bearer $token")
        return c
    }

    /** 在候选端口里找第一个 /health 返 200 的，结果缓存到 activePort。 */
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
                if (code == 200) {
                    activePort = p
                    return p
                }            } catch (_: Throwable) {
                // 换下一个端口
            }
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
