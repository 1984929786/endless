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

        /** 外部模型候选目录，按优先级排 */
        private val CANDIDATE_DIRS = listOf(
            "/storage/emulated/0/Download/qwen35-2b-int4",
            "/sdcard/Download/qwen35-2b-int4",
            "/storage/emulated/0/Download/qwen3-06b-cq4",
            "/sdcard/Download/qwen3-06b-cq4",
            "/storage/emulated/0/Download/Models/qwen35-2b-int4",
            "/storage/emulated/0/qwen35-2b-int4"
        )
    }

    @Volatile private var running = false
    private var serverSocket: ServerSocket? = null
    private val pool = Executors.newFixedThreadPool(2)

    /** 模型句柄，0 表示未加载 */
    @Volatile var handle: Long = 0L

    /**
     * 强引用传进 native 的模型路径字符串。
     * native 层保留 char 指针而不拷贝，GC 回收就是野指针。
     */
    @Volatile private var heldModelDir: String? = null

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

    /** 中断当前推理 */
    fun stopInference() {
        val h = handle
        if (h != 0L) {
            try { CactusEngine.stop(h) } catch (_: Throwable) {}
        }
    }

    /**
     * 模型目录解析，三级回退：
     * 1. 私有目录 models/current 已经有 bundle -> 直接用
     * 2. APK assets 里有 bundle -> 首次启动复制到私有目录
     * 3. 调试期外部存储 -> /sdcard/Download/qwen35-2b-int4
     */
    private fun resolveModelDir(progress: (String) -> Unit = {}): File {
        val current = File(context.filesDir, "models/current")

        // 有完成标记 = 上次已完整释放，直接信它（别每次都全量扫 327 个文件，慢且多余）
        if (File(context.filesDir, "models/.complete").isFile && File(current, "config.txt").isFile) {
            return current
        }

        // 已就绪
        if (isUsableModelDir(current)) return current

        // 从 assets 搬运
        if (hasAssetModel()) {
            progress("首次启动，正在释放内置模型（约 354MB，请稍候）")
            copyModelFromAssets(current, progress)
            if (isUsableModelDir(current)) return current
            progress("内置模型释放后仍不可用，尝试外部目录")
        }

        // 外部目录：多候选 + 逐条报告
        var found: File? = null
        for (p in CANDIDATE_DIRS) {
            val d = File(p)
            val ok = isUsableModelDir(d)
            val why = probeDir(d)
            progress("候选 $p -> " + (if (ok) "✓ 可用" else "✗ " + why))
            if (ok && found == null) found = d
        }
        if (found != null) return found

        progress("所有候选目录都不可用，回退到私有目录")
        return current
    }

    /** 扫描结果的详细原因，用于定位问题 */
    private fun probeDir(dir: File): String {
        if (!dir.exists()) return "不存在"
        if (!dir.isDirectory) return "不是目录"
        if (!dir.canRead()) return "无读权限"
        val files = dir.listFiles()
            ?: return "listFiles 返回 null（权限或分区存储限制）"
        val weights = files.count { it.name.endsWith(".weights") }
        val hasCfg = File(dir, "config.txt").isFile
        val hasTok = File(dir, "tokenizer_config.txt").isFile
        return "可读，文件 ${files.size} 个，.weights ${weights} 个，config.txt=${hasCfg}，tokenizer=${hasTok}"
    }

    /** 递归搜索 Download 下所有含 .weights 的目录，用于诊断 */
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
        val weights = files.count { it.name.endsWith(".weights") }
        if (weights > 0) {
            onLine("✓ 找到模型: ${dir.absolutePath}  ($weights 个 .weights)")
            return
        }
        for (f in files) {
            if (f.isDirectory && !f.name.startsWith(".")) walk(f, depth + 1, onLine)
        }
    }

    /** 判断一个目录是不是可用的模型目录 */
    private fun isUsableModelDir(dir: File): Boolean {
        if (!dir.isDirectory || !dir.canRead()) return false
        if (File(dir, "components/manifest.json").isFile) return true
        val files = dir.listFiles() ?: return false
        // v1.14 引擎要求：config.txt + 至少一个 .weights
        return File(dir, "config.txt").isFile && files.any { it.name.endsWith(".weights") }
    }

    /** APK 的 assets 里有没有内置模型 */
    private fun hasAssetModel(): Boolean {
        return try {
            val top = context.assets.list("models") ?: return false
            if (top.isEmpty()) return false
            // 兼容两种布局：models/current/... 或 models/...
            val probe = if (top.contains("current")) "models/current" else "models"
            val inside = context.assets.list(probe) ?: return false
            inside.isNotEmpty()
        } catch (t: Throwable) {
            false
        }
    }

    private fun assetModelRoot(): String {
        return try {
            val top = context.assets.list("models") ?: return "models/current"
            if (top.contains("current")) "models/current" else "models"
        } catch (t: Throwable) {
            "models/current"
        }
    }

    /**
     * 把 assets/models 递归复制到私有目录。
     *
     * 关键改变（2026-11-20，道友反馈每次打开都重新释放）：
     * 不再「全有或全无」。逐文件比对大小，已经完整的直接跳过，只有缺失或是短文件才重写。
     * 这样即使上次拷到第 200 个文件崩了，下次只补剩下的 127 个，不会从头重来。
     */
    private fun copyModelFromAssets(target: File, progress: (String) -> Unit) {
        val doneFlag = File(context.filesDir, "models/.complete")
        target.mkdirs()

        val root = assetModelRoot()

        var bytes = 0L          // 本轮实际写入字节
        var copied = 0           // 本轮实际写入文件数
        var skipped = 0          // 已存在且大小一致而跳过的文件数
        var blocks = 0
        val buf = ByteArray(256 * 1024)   // 256KB，小缓冲区降低内存峰值

        fun walk(assetPath: String, dest: File) {
            val list = context.assets.list(assetPath) ?: return
            if (list.isEmpty()) {
                dest.parentFile?.mkdirs()
                // 已存在且大小一致 → 跳过（增量补的关键）
                val expect = assetLength(assetPath)
                if (expect > 0 && dest.isFile && dest.length() == expect) {
                    skipped++
                    return
                }
                context.assets.open(assetPath).use { input ->
                    FileOutputStream(dest).use { out ->
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            bytes += n
                            blocks++
                            // 每 8MB 报一次进度，避免界面看着像卡死
                            if (blocks % 32 == 0) {
                                progress("释放中：${bytes / 1048576}MB（已补齐 ${copied + 1} 个文件）")
                            }
                        }
                        out.flush()
                    }
                }
                copied++
                return
            }
            dest.mkdirs()
            for (child in list) {
                val childAsset = if (assetPath.isEmpty()) child else "$assetPath/$child"
                walk(childAsset, File(dest, child))
            }
        }

        try {
            walk(root, target)
        } catch (t: Throwable) {
            // 拷一半失败：目录保留，已写好的文件下次会被跳过，不会再从头来
            Boot.trace(context, "释放模型失败: ${t.message}")
            throw t
        }

        try { doneFlag.writeText("copied=$copied skipped=$skipped bytes=$bytes") } catch (_: Throwable) {}
        progress("模型就绪：新写入 ${bytes / 1048576}MB，已有文件跳过 $skipped 个")
        Boot.trace(context, "模型释放完成 bytes=$bytes copied=$copied skipped=$skipped")
    }

    /** 取 asset 压缩前长度，失败返回 -1 */
    private fun assetLength(path: String): Long {
        return try {
            context.assets.openFd(path).use { it.length }
        } catch (_: Throwable) {
            -1L   // 压缩过的 asset 拿不到 fd，退化成每次覆盖（不会出错，只是慢一点）
        }
    }

    fun loadModelAsync(onProgress: (String) -> Unit = {}, onDone: (Boolean, String) -> Unit) {
        loading = true
        destroying = false   // 新的加载周期，清掉上一轮的销毁意愿
        Boot.trace(context, "loadModelAsync 开始")
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
                // 关键：native 层保留 char 指针不拷贝（官方 issue #66/#130），
                // 传进去的 String 必须在整个推理期强引用住，否则 GC 回收 = 野指针 = 无堆栈 SIGSEGV。
                // 用字段持有，不用局部变量。
                heldModelDir = dir.absolutePath
                val modelDir = dir.absolutePath
                Boot.trace(context, "nativeInit 开始: $modelDir")
                val h = CactusEngine.init(modelDir)
                val cost = System.currentTimeMillis() - t0
                Boot.trace(context, "nativeInit 返回 handle=$h 耗时${cost}ms")

                // init 期间被要求销毁（退出了应用）：直接收尾，不把句柄交出去
                if (destroying) {
                    if (h != 0L) { try { CactusEngine.destroy(h) } catch (_: Throwable) {} }
                    Log.i(TAG, "init 完成时已处于销毁状态，丢弃句柄")
                    onDone(false, "已退出")
                    return@execute
                }

                if (h == 0L) {
                    lastError = "cactus_init 失败: ${errText()}"
                    onDone(false, lastError); return@execute
                }
                handle = h
                modelReady = true
                loadedPath = dir.absolutePath
                CactusEngineHolder.currentHandle = h
                Log.i(TAG, "模型加载成功 handle=$h 耗时 ${cost}ms")
                onDone(true, "加载成功，耗时 ${cost}ms")
            } catch (t: Throwable) {
                lastError = "加载异常: ${t.message}"
                Log.e(TAG, "loadModel 失败", t)
                onDone(false, lastError)
            } finally {
                loading = false
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

    @Volatile private var destroying = false

    /**
     * 销毁模型。
     *
     * ⚠️ 绝不能在后加载线程还在跑 nativeInit 时调 nativeDestroy：
     * native 层 mmap 了权重文件，init 中途被 destroy 会直接 SIGSEGV（无 Java 堆栈）。
     * 这个环境里「进去就划掉」是最常见的操作，不防就等于必崩。
     */
    /**
     * 销毁模型。
     *
     * ⚠️ 绝不能在后加载线程还在跑 nativeInit 时调 nativeDestroy：
     * native 层 mmap 了权重文件，init 中途被 destroy 会直接 SIGSEGV（无 Java 堆栈）。
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
            try { CactusEngine.destroy(h) } catch (_: Throwable) {}
            handle = 0L
            modelReady = false
            loadedPath = ""
            CactusEngineHolder.currentHandle = 0L
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
        // 实测：v4 引擎的 nativeComplete 把 tools 当作第一个可空参数之后的位置，
        // 我们当前封装只透传 options；tools 暂时接在 options 里带过去（引擎认 options.tools 也认独立参数）。
        if (tools != null && tools.length() > 0) {
            opts.put("tools", normalizeTools(tools))
        }

        val t0 = System.currentTimeMillis()
        val raw = try {
            CactusEngine.complete(handle, clean.toString(), opts.toString())
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

    private fun normalizeTools(tools: JSONArray): JSONArray {
        // OpenAI 格式： [{type:"function", function:{name, description, parameters}}]
        // 实测引擎接受 OpenAI 原格式，直接返回数组（不能当字符串塞进 options，会被转义）
        return tools
    }

    /** Android 的 org.json 没有 optDouble(k, default)，自己写一个 */
    private fun numOr(o: JSONObject, k: String, def: Double): Double {
        if (!o.has(k) || o.isNull(k)) return def
        return try { o.getDouble(k) } catch (t: Throwable) { def }
    }

    private fun esc(s: String?): String = (s ?: "")
        .replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", "")
}
