package com.xundao.yongwuzhijing

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * 极薄适配桥：把 llama.cpp 本地模型包装成 OpenAI 兼容的 /v1/chat/completions。
 *
 * 设计要点：
 * 1. 只监听 127.0.0.1，外部不可达
 * 2. 绝不把对话内容发到云端
 * 3. 同步阻塞式：LlamaEngine.complete 内部用 runBlocking 收完 Flow，用线程池隔离
 */
class ModelBridge(private val context: Context) {

    companion object {
        private const val TAG = "ModelBridge"
        var port: Int = 18790
            private set

        /** 外部 GGUF 模型路径候选，调试时可以直接放这里 */
        private val EXTERNAL_GGUF_PATHS = listOf(
            "/storage/emulated/0/Download/Qwen3-1.7B-Q4_K_M.gguf",
            "/sdcard/Download/Qwen3-1.7B-Q4_K_M.gguf",
            "/storage/emulated/0/Download/model.gguf",
            "/sdcard/Download/model.gguf"
        )
    }

    @Volatile private var running = false
    private var serverSocket: ServerSocket? = null
    private val pool = Executors.newFixedThreadPool(2)

    /** 模型是否已加载（llama.cpp 没有数值句柄，用布尔标记） */
    @Volatile var handle: Long = 0L

    /**
     * 强引用当前模型路径（保留字段名，实际已不需要防野指针，
     * 但留着方便日志和调试时看加载的是哪个文件）。
     */
    @Volatile private var heldModelPath: String? = null

    /** 模型加载中标记（防止加载中途被 destroy） */
    @Volatile var loading: Boolean = false
        private set

    @Volatile var modelReady = false
        private set

    @Volatile var lastError: String = ""
        private set

    @Volatile private var loadedPath: String = ""

    /** 当前已加载的模型目录 */
    fun currentModelPath(): String = loadedPath

    /** 中断当前推理（llama.cpp 没有 stop 接口，这里只能标记；推理会自然跑完或超时） */
    fun stopInference() {
        Stage.mark("stopInference 被调用（llama.cpp 无硬中断）")
    }

    /**
     * 模型文件解析（GGUF 单文件，比 Cactus 的多文件目录简单得多）。
     *
     * 查找顺序：
     * 1. 私有目录 models 下的 .gguf —— 已释放过就直接用
     * 2. APK assets/models 下的 .gguf —— 首次启动拷到私有目录
     * 3. 外部 Download 下同名文件 —— 调试用
     */
    /**
     * 模型文件是否已经在私有目录里（与进程生命周期无关）。
     * 判断依据是文件本身，「模型已释放」这个事实不因 App 被杀而消失。
     * 这样冷启动时 UI 能区分「还要释放」(idle) 和「只需加载」(staged)。
     */
    fun hasStagedModel(): Boolean {
        return try {
            findGguf(File(context.filesDir, "models")) != null
        } catch (t: Throwable) {
            false
        }
    }

    private fun resolveModelFile(progress: (String) -> Unit = {}): File? {
        val modelsDir = File(context.filesDir, "models")

        // 1. 私有目录已有
        findGguf(modelsDir)?.let {
            progress("使用已释放的模型：${it.name}（${it.length() / 1048576}MB）")
            return it
        }

        // 2. 从 assets 拷（单文件，一次性）
        val assetName = findAssetGguf()
        if (assetName != null) {
            progress("首次启动，正在释放内置模型…")
            val out = copyAssetGguf(assetName, modelsDir, progress)
            if (out != null) return out
            progress("内置模型释放失败")
        }

        // 3. 外部目录
        for (p in EXTERNAL_GGUF_PATHS) {
            val f = File(p)
            if (f.isFile) {
                progress("使用外部模型：$p")
                return f
            }
        }

        progress("找不到任何 .gguf 模型文件")
        return null
    }

    /** 在目录里找第一个 .gguf */
    private fun findGguf(dir: File): File? {
        if (!dir.isDirectory) return null
        return dir.listFiles()?.firstOrNull { it.isFile && it.name.endsWith(".gguf") }
    }

    /** assets/models 下第一个 .gguf 的文件名 */
    private fun findAssetGguf(): String? {
        return try {
            val top = context.assets.list("models") ?: return null
            // 兼容 models/xxx.gguf 和 models/current/xxx.gguf
            if (top.contains("current")) {
                val inside = context.assets.list("models/current") ?: return null
                inside.firstOrNull { it.endsWith(".gguf") }?.let { return "models/current/$it" }
            }
            top.firstOrNull { it.endsWith(".gguf") }?.let { return "models/$it" }
            null
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * 把单个 GGUF 从 assets 复制到私有目录。
     * 先写 .part 再改名，中途被杀不会留下半截文件冒充完整模型。
     */
    private fun copyAssetGguf(assetPath: String, destDir: File, progress: (String) -> Unit): File? {
        val name = assetPath.substringAfterLast('/')
        val dest = File(destDir, name)
        val tmp = File(destDir, "$name.part")
        destDir.mkdirs()

        // 已经拷完整了（有 .gguf 无 .part）→ 直接用
        if (dest.isFile && dest.length() > 0 && !tmp.exists()) return dest

        return try {
            tmp.delete()
            var bytes = 0L
            val buf = ByteArray(1024 * 1024)
            context.assets.open(assetPath).use { input ->
                java.io.FileOutputStream(tmp).use { out ->
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        bytes += n
                        out.flush()
                        // 每 32MB 报一次，避免界面看着像卡死
                        if (bytes % (32 * 1048576L) < 1048576L) {
                            progress("释放中：${bytes / 1048576}MB")
                        }
                    }
                    out.fd.sync()
                }
            }
            if (tmp.length() <= 0) {
                tmp.delete()
                Stage.mark("释放 GGUF 失败：写出 0 字节")
                return null
            }
            if (!tmp.renameTo(dest)) {
                Stage.mark("释放 GGUF 失败：改名失败")
                tmp.delete()
                return null
            }
            Stage.mark("GGUF 释放完成：${dest.name} ${dest.length() / 1048576}MB")
            progress("模型就绪：${dest.length() / 1048576}MB")
            dest
        } catch (t: Throwable) {
            Stage.mark("释放 GGUF 异常：${t.javaClass.simpleName}: ${t.message}")
            tmp.delete()
            null
        }
    }

    /** 递归搜索 Download 下 .gguf，用于诊断 */
    fun scanDownloadDirs(onLine: (String) -> Unit) {
        val roots = listOf(
            "/storage/emulated/0/Download",
            "/sdcard/Download",
            "/storage/emulated/0"
        )
        for (r in roots) {
            val root = File(r)
            onLine("扫描 $r -> " + if (!root.exists()) "不存在"
                else if (!root.canRead()) "无权限"
                else "可读")
            if (!root.canRead()) continue
            walk(root, 0, onLine)
        }
    }

    private fun walk(dir: File, depth: Int, onLine: (String) -> Unit) {
        if (depth > 3) return
        val files = dir.listFiles() ?: return
        val ggufs = files.count { it.name.endsWith(".gguf") }
        if (ggufs > 0) {
            onLine("✓ 找到模型: ${dir.absolutePath}  ($ggufs 个 .gguf)")
            return
        }
        for (f in files) {
            if (f.isDirectory && !f.name.startsWith(".")) walk(f, depth + 1, onLine)
        }
    }

    fun loadModelAsync(onProgress: (String) -> Unit = {}, onDone: (Boolean, String) -> Unit) {
        loading = true
        destroying = false   // 新的加载周期，清掉上一轮的销毁意愿
        Boot.trace(context, "loadModelAsync 开始")
        pool.execute {
            try {
                Stage.mark("loadModelAsync: 进入后台线程")
                val modelFile = resolveModelFile { msg -> Log.i(TAG, msg); onProgress(msg) }
                if (modelFile == null) {
                    lastError = "找不到模型文件（.gguf）"
                    Stage.mark("加载失败：找不到 gguf")
                    onDone(false, lastError); return@execute
                }
                Stage.mark("resolveModelFile 完成 -> ${modelFile.absolutePath} ${modelFile.length() / 1048576}MB")
                Log.i(TAG, "加载模型: ${modelFile.absolutePath}")

                modelReady = false
                val t0 = System.currentTimeMillis()
                heldModelPath = modelFile.absolutePath

                // ---- 逐步进入 native，每一步都先落盘 ----
                Stage.mark("即将 LlamaEngine.load()")
                val ok = LlamaEngine.load(context, modelFile)
                val cost = System.currentTimeMillis() - t0
                Stage.mark("LlamaEngine.load 返回 $ok 耗时${cost}ms")

                if (destroying) {
                    LlamaEngine.unload()
                    Log.i(TAG, "加载完成时已处于销毁状态，卸载模型")
                    onDone(false, "已退出")
                    return@execute
                }

                if (!ok) {
                    lastError = "模型加载失败（llama.cpp）"
                    Stage.mark("加载失败：$lastError")
                    onDone(false, lastError); return@execute
                }
                modelReady = true
                loadedPath = modelFile.absolutePath
                Log.i(TAG, "模型加载成功 耗时 ${cost}ms")
                onDone(true, "加载成功，耗时 ${cost}ms")
            } catch (t: Throwable) {
                lastError = "加载异常: ${t.message}"
                Stage.mark("loadModel 异常: ${t.javaClass.simpleName}: ${t.message}")
                Log.e(TAG, "loadModel 失败", t)
                onDone(false, lastError)
            } finally {
                loading = false
                Stage.mark("loadModelAsync 退出")
            }
        }
    }

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

    @Volatile private var destroying = false

    /**
     * 销毁模型。
     *
     * ⚠️ 绝不能在后加载线程还在跑 native loadModel 时调 cleanUp：
     * native 层 mmap 了权重文件，加载中途被销毁会直接 SIGSEGV（无 Java 堆栈）。
     * 这个环境里「进去就划掉」是最常见的操作，不防就等于必崩。
     */
    fun destroyModel() {
        destroying = true
        // 加载中：只做标记，loadModelAsync 结束后自己丢弃句柄
        if (loading) {
            Log.i(TAG, "请求销毁时模型正在加载，标记后交由加载线程收尾")
            return
        }
        val h = handle
        if (h != 0L) {
            LlamaEngine.unload()
            handle = 0L
            modelReady = false
            loadedPath = ""
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

        // llama.cpp 走 chat template，我们把 system 和最后的 user 拆开
        var systemPrompt: String? = null
        val promptBuf = StringBuilder()
        for (i in 0 until messages.length()) {
            val m = messages.optJSONObject(i) ?: continue
            val role = m.optString("role", "user")
            val content = m.optString("content", "")
            when (role) {
                "system" -> systemPrompt = if (systemPrompt.isNullOrBlank()) content else "$systemPrompt\n$content"
                "assistant" -> promptBuf.append("助手：").append(content).append("\n")
                else -> promptBuf.append("用户：").append(content).append("\n")
            }
        }
        val prompt = promptBuf.toString().trim()
        if (prompt.isEmpty()) {
            return """{"error":{"message":"消息为空","type":"bad_request"}}"""
        }

        val t0 = System.currentTimeMillis()
        val raw = try {
            LlamaEngine.complete(prompt, systemPrompt)
        } catch (t: Throwable) {
            return """{"error":{"message":"推理失败: ${esc(t.message)}","type":"inference_error"}}"""
        }
        if (raw == null) {
            return """{"error":{"message":"推理失败（引擎未就绪或异常）","type":"inference_error"}}"""
        }

        // llama.cpp 直接返回纯文本，不做工具调用
        val text = raw
        val model = req.optString("model", "local-qwen3-0.6b")
        val id = "chatcmpl-" + System.currentTimeMillis()
        val created = System.currentTimeMillis() / 1000

        val outMsg = JSONObject()
        outMsg.put("role", "assistant")
        outMsg.put("content", text)

        val choice = JSONObject()
            .put("index", 0)
            .put("message", outMsg)
            .put("finish_reason", "stop")

        val usage = JSONObject()
            .put("prompt_tokens", 0)
            .put("completion_tokens", 0)
            .put("total_tokens", 0)

        val resp = JSONObject()
            .put("id", id)
            .put("object", "chat.completion")
            .put("created", created)
            .put("model", model)
            .put("choices", JSONArray().put(choice))
            .put("usage", usage)

        Log.i(TAG, "推理完成 ${System.currentTimeMillis() - t0}ms, ${text.length} 字符")
        return resp.toString()
    }

    private fun esc(s: String?): String = (s ?: "")
        .replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", "")
}
