package com.xundao.yongwuzhijing

import com.cactus.CactusEngine

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * 极薄适配桥：把 Cactus 本地模型包装成 OpenAI 兼容的 /v1/chat/completions。
 *
 * 设计要点：
 * 1. 只监听 127.0.0.1，外部不可达
 * 2. auto_handoff 强制关闭 —— 绝不把对话内容发到云端
 * 3. 同步阻塞式：Cactus 的 nativeComplete 本身就是阻塞的，用线程池隔离
 */
class ModelBridge(private val context: Context) {

    companion object {
        private const val TAG = "ModelBridge"
        var port: Int = 18790
            private set
    }

    @Volatile private var running = false
    private var serverSocket: ServerSocket? = null
    private val pool = Executors.newFixedThreadPool(2)

    /** 模型句柄，0 表示未加载 */
    @Volatile var handle: Long = 0L
        private set

    @Volatile var modelReady = false
        private set

    @Volatile var lastError: String = ""
        private set

    /**
     * 模型目录解析，三级回退：
     * 1. 私有目录 models/current 已经有 bundle -> 直接用
     * 2. APK assets 里有 bundle -> 首次启动复制到私有目录
     * 3. 调试期外部存储 -> /sdcard/Download/qwen35-2b-int4
     */
    private fun resolveModelDir(progress: (String) -> Unit = {}): File {
        val current = File(context.filesDir, "models/current")

        // 已就绪
        if (isUsableModelDir(current)) return current

        // 从 assets 搬运
        if (hasAssetModel()) {
            progress("首次启动，正在释放内置模型（约 1.4GB，请稍候）")
            copyModelFromAssets(current, progress)
            if (isUsableModelDir(current)) return current
            progress("内置模型释放后仍不可用，尝试外部目录")
        }

        val ext = File("/storage/emulated/0/Download/qwen35-2b-int4")
        if (isUsableModelDir(ext)) return ext

        return current
    }

    /** 判断一个目录是不是可用的模型目录 */
    private fun isUsableModelDir(dir: File): Boolean {
        if (!dir.isDirectory) return false
        if (File(dir, "components/manifest.json").isFile) return true
        val files = dir.listFiles() ?: return false
        return files.any { it.name.endsWith(".weights") }
    }

    /** APK 的 assets 里有没有内置模型 */
    private fun hasAssetModel(): Boolean = try {
        context.assets.list("models")?.isNotEmpty() == true
    } catch (t: Throwable) { false }

    /**
     * 把 assets/models 递归复制到私有目录。
     * 先写到 .staging 再原子改名，避免中途被杀留下半截目录。
     */
    private fun copyModelFromAssets(target: File, progress: (String) -> Unit) {
        val staging = File(context.filesDir, "models/.staging")
        if (staging.exists()) staging.deleteRecursively()
        staging.mkdirs()

        var blocks = 0
        var bytes = 0L
        val buf = ByteArray(1 shl 20)

        fun walk(assetPath: String, dest: File) {
            val list = context.assets.list(assetPath) ?: return
            if (list.isEmpty()) {
                dest.parentFile?.mkdirs()
                context.assets.open(assetPath).use { input ->
                    FileOutputStream(dest).use { out ->
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            bytes += n
                            blocks++
                            if (blocks % 8000 == 0) progress("释放中：${bytes / 1048576}MB")
                        }
                    }
                }
                return
            }
            dest.mkdirs()
            for (child in list) {
                val childAsset = if (assetPath.isEmpty()) child else "$assetPath/$child"
                walk(childAsset, File(dest, child))
            }
        }

        walk("models", staging)

        if (target.exists()) target.deleteRecursively()
        target.parentFile?.mkdirs()
        if (!staging.renameTo(target)) {
            target.mkdirs()
            staging.copyRecursively(target, overwrite = true)
            staging.deleteRecursively()
        }
        progress("模型就绪：${bytes / 1048576}MB")
        Log.i(TAG, "模型释放完成 ${bytes / 1048576}MB -> ${target.absolutePath}")
    }
    fun loadModelAsync(onProgress: (String) -> Unit = {}, onDone: (Boolean, String) -> Unit) {
        pool.execute {
            try {
                val dir = resolveModelDir { msg -> Log.i(TAG, msg); onProgress(msg) }
                Log.i(TAG, "加载模型: ${dir.absolutePath}")
                if (!dir.isDirectory) {
                    lastError = "模型目录不存在: ${dir.absolutePath}"
                    onDone(false, lastError); return@execute
                }
                modelReady = false
                val t0 = System.currentTimeMillis()
                handle = CactusEngine.init(dir.absolutePath)
                val cost = System.currentTimeMillis() - t0
                if (handle == 0L) {
                    lastError = "cactus_init 失败: ${errText()}"
                    onDone(false, lastError); return@execute
                }
                modelReady = true
                CactusEngineHolder.currentHandle = handle
                Log.i(TAG, "模型加载成功 handle=$handle 耗时 ${cost}ms")
                onDone(true, "加载成功，耗时 ${cost}ms")
            } catch (t: Throwable) {
                lastError = "加载异常: ${t.message}"
                Log.e(TAG, "loadModel 失败", t)
                onDone(false, lastError)
            }
        }
    }

    private fun errText(): String = try { CactusEngine.lastError() } catch (t: Throwable) { "" }

    fun startServer() {
        if (running) return
        running = true
        pool.execute {
            try {
                val ss = ServerSocket(port, 16, java.net.InetAddress.getByName("127.0.0.1"))
                serverSocket = ss
                Log.i(TAG, "HTTP 服务启动 127.0.0.1:$port")
                while (running) {
                    val s = try { ss.accept() } catch (t: Throwable) { null } ?: continue
                    pool.execute { handleConn(s) }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "server 异常", t)
                lastError = "server: ${t.message}"
                running = false
            }
        }
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Throwable) {}
        serverSocket = null
    }

    fun destroyModel() {
        if (handle != 0L) {
            try { CactusEngine.destroy(handle) } catch (_: Throwable) {}
            handle = 0L
            modelReady = false
        }
    }

    // ---------- HTTP ----------

    private fun handleConn(sock: Socket) {
        sock.use { s ->
            try {
                val input = BufferedInputStream(s.getInputStream())
                val out = BufferedOutputStream(s.getOutputStream())

                val head = readHead(input) ?: return
                val lines = head.split("\r\n")
                val reqLine = lines[0].split(" ")
                if (reqLine.size < 3) return
                val method = reqLine[0]
                val path = reqLine[1].substringBefore('?')

                var contentLength = 0
                for (i in 1 until lines.size) {
                    val l = lines[i]
                    val c = l.indexOf(':')
                    if (c > 0 && l.substring(0, c).trim().equals("Content-Length", true)) {
                        contentLength = l.substring(c + 1).trim().toIntOrNull() ?: 0
                    }
                }
                val body = ByteArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val n = input.read(body, read, contentLength - read)
                    if (n < 0) break
                    read += n
                }

                when {
                    path == "/health" -> {
                        val o = JSONObject()
                        o.put("ok", true)
                        o.put("model_ready", modelReady)
                        o.put("error", lastError)
                        writeJson(out, 200, o.toString())
                    }
                    path == "/v1/models" -> {
                        val o = JSONObject()
                        val arr = JSONArray()
                        arr.put(JSONObject().put("id", "local-qwen3.5-2b").put("object", "model").put("owned_by", "local"))
                        o.put("object", "list").put("data", arr)
                        writeJson(out, 200, o.toString())
                    }
                    path == "/v1/chat/completions" && method == "POST" -> {
                        val resp = chatCompletion(String(body, Charsets.UTF_8))
                        writeJson(out, 200, resp)
                    }
                    else -> writeJson(out, 404, """{"error":"not found"}""")
                }
                out.flush()
            } catch (t: Throwable) {
                Log.e(TAG, "连接处理失败", t)
            }
        }
    }

    /** 读取请求头，直到空行 */
    private fun readHead(input: InputStream): String? {
        val sb = StringBuilder()
        var prev = -1
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            sb.append(b.toChar())
            if (prev == '\r'.code && b == '\n'.code) break
            prev = b
        }
        return sb.toString()
    }

    private fun writeJson(out: OutputStream, code: Int, json: String) {
        val b = json.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $code OK\r\n" +
                "Content-Type: application/json; charset=utf-8\r\n" +
                "Content-Length: ${b.size}\r\n" +
                "Connection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.UTF_8))
        out.write(b)
    }

    /**
     * OpenAI /v1/chat/completions -> Cactus
     */
    private fun chatCompletion(reqBody: String): String {
        if (!modelReady || handle == 0L) {
            return """{"error":{"message":"模型尚未加载","type":"model_not_ready"}}"""
        }
        val req = try { JSONObject(reqBody) } catch (t: Throwable) {
            return """{"error":{"message":"请求 JSON 解析失败","type":"bad_request"}}"""
        }

        val messages = req.optJSONArray("messages") ?: JSONArray()
        // 去掉 OpenAI 专有但 Cactus 不认的字段，只保留 role/content
        val clean = JSONArray()
        for (i in 0 until messages.length()) {
            val m = messages.optJSONObject(i) ?: continue
            val o = JSONObject()
            o.put("role", m.optString("role", "user"))
            o.put("content", m.optString("content", ""))
            clean.put(o)
        }

        // 采样参数
        val opts = JSONObject()
        opts.put("max_tokens", req.optInt("max_tokens", 1024))
        opts.put("temperature", numOr(req, "temperature", 0.7))
        opts.put("top_p", numOr(req, "top_p", 0.95))
        opts.put("min_p", 0.05)
        opts.put("repetition_penalty", 1.05)
        // 首版纯本地：绝不允许云端兜底，否则对话内容会外泄
        opts.put("auto_handoff", false)
        opts.put("enable_thinking_if_supported", false)

        val tools = req.optJSONArray("tools")
        val toolsJson = if (tools != null && tools.length() > 0) {
            // Cactus 要的是 OpenAI tools 数组本身
            normalizeTools(tools)
        } else null

        val t0 = System.currentTimeMillis()
        val raw = try {
            CactusEngine.complete(handle, clean.toString(), opts.toString(), toolsJson)
        } catch (t: Throwable) {
            return """{"error":{"message":"推理失败: ${esc(t.message)}","type":"inference_error"}}"""
        }
        if (raw == null || raw.isEmpty()) {
            return """{"error":{"message":"推理无返回: ${esc(errText())}","type":"inference_error"}}"""
        }

        val c = try { JSONObject(raw) } catch (t: Throwable) {
            return """{"error":{"message":"返回 JSON 解析失败: ${esc(raw.take(200))}","type":"bad_response"}}"""
        }
        if (!c.optBoolean("success", true)) {
            return """{"error":{"message":"${esc(c.optString("error", "未知错误"))}","type":"inference_error"}}"""
        }

        val text = c.optString("response", "")
        val model = req.optString("model", "local-qwen3.5-2b")
        val id = "chatcmpl-" + System.currentTimeMillis()
        val created = System.currentTimeMillis() / 1000

        // 工具调用：Cactus 给 function_calls，转成 OpenAI tool_calls
        val fcs = c.optJSONArray("function_calls")
        val outMsg = JSONObject()
        outMsg.put("role", "assistant")
        if (fcs != null && fcs.length() > 0) {
            outMsg.put("content", if (text.isEmpty()) JSONObject.NULL else text)
            val tcs = JSONArray()
            for (i in 0 until fcs.length()) {
                val fc = fcs.optJSONObject(i) ?: continue
                val name = fc.optString("name", fc.optString("function", ""))
                val args = fc.opt("arguments") ?: fc.opt("parameters") ?: JSONObject()
                val argsStr = if (args is String) args else args.toString()
                tcs.put(JSONObject()
                    .put("id", "call_" + System.currentTimeMillis() + "_" + i)
                    .put("type", "function")
                    .put("function", JSONObject().put("name", name).put("arguments", argsStr)))
            }
            outMsg.put("tool_calls", tcs)
        } else {
            outMsg.put("content", text)
        }

        val choice = JSONObject()
            .put("index", 0)
            .put("message", outMsg)
            .put("finish_reason", if (fcs != null && fcs.length() > 0) "tool_calls" else "stop")

        val usage = JSONObject()
            .put("prompt_tokens", c.optInt("prefill_tokens", 0))
            .put("completion_tokens", c.optInt("decode_tokens", 0))
            .put("total_tokens", c.optInt("total_tokens", 0))

        val resp = JSONObject()
            .put("id", id)
            .put("object", "chat.completion")
            .put("created", created)
            .put("model", model)
            .put("choices", JSONArray().put(choice))
            .put("usage", usage)

        Log.i(TAG, "推理完成 ${System.currentTimeMillis() - t0}ms, ${numOr(c, "decode_tps", 0.0)} tok/s, ${numOr(c, "ram_usage_mb", 0.0)}MB")
        return resp.toString()
    }

    private fun normalizeTools(tools: JSONArray): String {
        // OpenAI 格式： [{type:"function", function:{name, description, parameters}}]
        // Cactus 文档说传 tools 定义，实测先原样传，失败再调
        return tools.toString()
    }

    /** Android 的 org.json 没有 optDouble(k, default)，自己写一个 */
    private fun numOr(o: JSONObject, k: String, def: Double): Double {
        if (!o.has(k) || o.isNull(k)) return def
        return try { o.getDouble(k) } catch (t: Throwable) { def }
    }

    private fun esc(s: String?): String = (s ?: "")
        .replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", "")
}
