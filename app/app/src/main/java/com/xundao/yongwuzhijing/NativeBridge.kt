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

    /** JS 轮询：返回 ready / loading:进度 / fail:原因 / staged:模型文件已就绪待加载 / idle */
    @JavascriptInterface
    fun status(): String {
        // Agent 可用时，不需要本地模型也能聊天（工具全有）
        if (AgentClient.enabled && AgentClient.probe()) return "agent"
        // probe 失败时把原写进 stage 日志：不聊天也能看出网关连通性
        if (AgentClient.enabled && AgentClient.lastProbeError.isNotBlank()) {
            CrashCatcher.stage("启动探活失败: " + AgentClient.lastProbeError)
        }
        if (bridge.modelReady) return "ready"
        val err = lastLoadError
        if (err != null) return "fail:$err"
        if (loading.get()) return "loading:" + (lastProgress ?: "正在加载模型…")
        // 模型文件已经释放到私有目录了 —— 不是「还要释放」，只是需要加载进内存。
        // 这个状态要持久化判断（看文件，不看进程内变量），冷启动也能认出来。
        if (bridge.hasStagedModel()) return "staged"
        return "idle"
    }

    /** 当前是谁在回答：agent（有工具）/ local（仅聊天）/ off */
    @JavascriptInterface
    fun mode(): String {
        if (!AgentClient.enabled) return "off"
        return if (AgentClient.probe()) "agent" else "local"
    }

    /** 开关 Agent（工具能力）。关掉后只用本地模型。 */
    @JavascriptInterface
    fun setAgentEnabled(on: Boolean): String {
        AgentClient.enabled = on
        CrashCatcher.stage("Agent 开关: $on")
        return if (on) "on" else "off"
    }

    @JavascriptInterface
    fun gwInfo(): String {
        val o = JSONObject()
        val gw = GatewayLauncher.get(context)
        o.put("port", GatewayLauncher.PORT)
        o.put("proc", GatewayLauncher.procAlive())
        o.put("probe", AgentClient.probe())
        o.put("health", AgentClient.healthLine())
        o.put("tokenHint", if (AgentClient.token.isEmpty()) "(空)" else AgentClient.token.take(8) + "…" + AgentClient.token.length + "位")
        o.put("home", java.io.File(context.filesDir, "openclaw/home").absolutePath)
        o.put("lastNote", AgentClient.lastChatNote)
        o.put("err", AgentClient.lastProbeError)
        o.put("steps", gw.diag())
        // 端点开没开：直接看配置文件，不猜
        val cfg = java.io.File(context.filesDir, "openclaw/home/openclaw.json")
        val txt = try { if (cfg.exists()) cfg.readText() else "" } catch (e: Throwable) { "" }
        o.put("chatEndpoint", txt.contains("chatCompletions"))
        o.put("hasConfig", cfg.exists())
        // 运行时有没有被释放出来
        o.put("extracted", java.io.File(context.filesDir, "openclaw/runtime/openclaw.mjs").exists())
        o.put("runtimeFiles", countFiles(java.io.File(context.filesDir, "openclaw/runtime")))
        // 网关日志尾部：排障时不用拔手机
        o.put("logTail", tail(java.io.File(context.filesDir, "openclaw/gateway.log"), 40))
        return o.toString()
    }

    /** 手动拉起网关（等释放+启动完才返回結果，可能要几十秒） */
    @JavascriptInterface
    fun gwStart(): String {
        val t0 = System.currentTimeMillis()
        return try {
            val gw = GatewayLauncher.get(context)
            val ok = gw.start()
            val ms = System.currentTimeMillis() - t0
            if (ok) {
                AgentClient.loadToken(java.io.File(context.filesDir, "openclaw/home/openclaw.json"))
                "已启动 ${ms}ms | " + gw.diag()
            } else {
                "启动失败 ${ms}ms | " + gw.diag()
            }
        } catch (t: Throwable) {
            "异常: ${t.javaClass.simpleName}: ${t.message}"
        }
    }

    /** 真的发一句话给网关。返回可读结果，界面直接显示。 */
    @JavascriptInterface
    fun gwTest(): String {
        val t0 = System.currentTimeMillis()
        val r = AgentClient.chat(listOf(Triple("user", "只回答两个字：在线", "")), "ywzj-diag")
        val ms = System.currentTimeMillis() - t0
        return if (r.ok) "成功 ${ms}ms: " + r.text.take(60)
        else "失败 ${ms}ms: " + r.error + " | " + AgentClient.lastChatNote
    }

    /** Agent 开关当前值 */
    @JavascriptInterface
    fun agentEnabled(): Boolean = AgentClient.enabled

    private fun countFiles(dir: java.io.File): Int {
        var n = 0
        try {
            dir.walkTopDown().forEach { if (it.isFile) n++ }
        } catch (_: Throwable) {
        }
        return n
    }

    private fun tail(f: java.io.File, n: Int): String {
        return try {
            if (!f.exists()) return "(无 gateway.log)"
            f.readLines().takeLast(n).joinToString("\n")
        } catch (e: Throwable) {
            "(读日志失败: ${e.message})"
        }
    }

    /** 诊断摘要：上次完整调用卡在哪一环。装机不用翻日志。
     *  注意：这里**不再调 probe()**，只用缓存值。
     *  diag 是从 JS 异步拿的，再探一次会把结果拖后 3 秒甚至卡死。 */
    @JavascriptInterface
    fun diag(): String = "src=" + lastSource + " " + diagDetail()

    /** 裸 Socket 自检：分别测 ipv4/ipv6/localhost，看是哪一层被拦。 */
    @JavascriptInterface
    fun netTest(): String = AgentClient.selfTest()

    /** 上一轮回答的链路：gateway / local / none。最硬的事实，不靠措辞猜。 */
    @Volatile private var lastSource: String = "none"

    private fun diagDetail(): String {
        val sb = StringBuilder()
        sb.append("enabled=").append(AgentClient.enabled)
        sb.append(" probe=").append(if (AgentClient.lastProbeOk()) "OK" else "FAIL")
        if (AgentClient.lastProbeError.isNotBlank()) {
            sb.append(" err=").append(AgentClient.lastProbeError)
        }
        sb.append(" last=").append(AgentClient.lastChatNote.ifBlank { "(未调用过)" })
        sb.append(" model=").append(if (bridge.modelReady) "ready" else "not-ready")
        // 网关不通时直接带上裸 Socket 自检，省得来回几轮。
        // 只在 probe 失败时测，成功时不做无用的网络动作。
        if (!AgentClient.lastProbeOk()) sb.append(" net=").append(AgentClient.selfTest())
        return sb.toString()
    }

    /** 读崩溃日志（给用户看 / 发给我） */
    @JavascriptInterface
    fun crashLog(): String = CrashCatcher.read(context)

    @JavascriptInterface
    fun lastStage(): String = CrashCatcher.lastStageText(context)

    /** 内存状况，排查 OOM */
    @JavascriptInterface
    fun memInfo(): String {
        return try {
            val act = Runtime.getRuntime()
            val sb = StringBuilder()
            sb.append("heap").append(" ").append((act.totalMemory() - act.freeMemory()) / 1048576).append("MB/")
                .append(act.maxMemory() / 1048576).append("MB")
            val am = context.getSystemService(android.content.Context.ACTIVITY_SERVICE)
                    as android.app.ActivityManager
            val mi = android.app.ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            sb.append(" | 系统可用 ").append(mi.availMem / 1048576).append("MB")
            sb.append(" | 低内存=").append(mi.lowMemory)
            sb.toString()
        } catch (t: Throwable) {
            "未知"
        }
    }

    /** 把崩溃日志导出到应用外部可读的地方 */
    @JavascriptInterface
    fun exportCrash(): String {
        return try {
            val txt = "stage: " + CrashCatcher.lastStageText(context) + "\n\n" + CrashCatcher.read(context)
            if (txt.isBlank()) return "没有崩溃记录"
            val f = File(context.getExternalFilesDir(null), "crash-${System.currentTimeMillis()}.txt")
            f.writeText(txt)
            f.absolutePath
        } catch (t: Throwable) {
            "导出失败: ${t.message}"
        }
    }

    @Volatile private var lastProgress: String? = null
    @Volatile private var lastLoadError: String? = null

    /**
     * 整个进程只允许调用一次 nativeInit。
     *
     * 根因（官方 issue #130，维护者原话）：
     *   "stale per-prefix engine state" —— v1.14 引擎在重复 init 时会恢复
     *   陈旧的 KV/decode 状态，没恢复全 → SIGSEGV（无 Java 堆栈）。
     * 修复只在 main 分支（v4.x），v1.14 没有。
     *
     * 所以：init 只能成功一次。失败也不再重试（重试 = 第二次 init = 必崩），
     * 而是告诉用户重启应用。
     */
    @Volatile private var initAttempted = false

    private fun startLoadAsync() {
        if (initAttempted) {
            // 已经试过了。若模型已就绪就正常；否则明确告知必须重启
            if (bridge.modelReady) {
                lastProgress = "就绪"
                lastLoadError = null
            } else {
                lastLoadError = "引擎初始化失败，请完全退出应用（从最近任务划掉）后重新打开"
            }
            return
        }
        if (!loading.compareAndSet(false, true)) return
        initAttempted = true
        lastLoadError = null
        lastProgress = "正在释放内置模型…"
        CrashCatcher.stage("开始异步加载模型")
        Thread {
            try {
                bridge.loadModelAsync(
                    onProgress = { msg ->
                        lastProgress = msg
                        CrashCatcher.stage(msg)
                        Log.i(TAG, msg)
                    },
                    onDone = { ok, msg ->
                        loading.set(false)
                        if (ok) {
                            lastProgress = "就绪"
                            lastLoadError = null
                            CrashCatcher.stage("模型加载成功")
                        } else {
                            lastLoadError = msg
                            CrashCatcher.stage("模型加载失败: $msg")
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
     * 推理。同步返回结果字符串，JS 在独立线程调用。
     * 不能用「JS 线程 join 后台线程」——JS 线程持着锁 join 会造成 stalling。
     * 直接在调用线程里跑 native（WebView 的 JS 调用本来就跑在非 UI 线程）。
     */
    @JavascriptInterface
    fun complete(payloadJson: String): String {
        CrashCatcher.stage("complete 进入")

        // 解析消息（本地模型和 Agent 都要用）
        val messages: List<Triple<String, String, String>>
        try {
            val payload = JSONObject(payloadJson)
            val arr = payload.optJSONArray("messages") ?: JSONArray()
            val list = ArrayList<Triple<String, String, String>>(arr.length())
            for (i in 0 until arr.length()) {
                val m = arr.getJSONObject(i)
                list.add(Triple(m.optString("role", "user"), m.optString("content", ""), ""))
            }
            messages = list
        } catch (t: Throwable) {
            Log.e(TAG, "payload 解析失败", t)
            return ""
        }

        // ① 先问 OpenClaw（有手：文件/命令/浏览器/定时）
        //    规格书：本地模型是脑，OpenClaw 是手。只靠 0.6B 是哑巴。
        CrashCatcher.stage("Agent 尝试: enabled=${AgentClient.enabled} 消息${messages.size}条")
        if (AgentClient.enabled) {
            AgentClient.resetProbe()
            val r = AgentClient.chat(messages)
            if (r.ok) {
                lastSource = "gateway"
                CrashCatcher.stage("Agent 回复成功: ${AgentClient.lastChatNote}")
                return r.text
            }
            lastSource = "local"
            Log.i(TAG, "Agent 不可用，回退本地模型: ${r.error}")
            CrashCatcher.stage("Agent 回退: ${r.error} | ${AgentClient.lastChatNote}")
        } else {
            lastSource = "local"
        }

        // ② 回退：本地模型（断网/网关没起时仍然能对话）
        if (!bridge.modelReady) return ""
        if (!busy.compareAndSet(false, true)) return ""

        CrashCatcher.stage("本地推理开始")
        try {
            val msgs = JSONArray()
            for (msg in messages) {
                msgs.put(
                    JSONObject()
                        .put("role", msg.first)
                        .put("content", msg.second)
                )
            }

            val raw = try {
                // llama.cpp 已开 Jinja 聊天模板（ai_chat.cpp use_jinja=true），
                // 模板会自动套 <|im_start|>user … <|im_start|>assistant。
                // 这里千万不能再手写「用户：」「助手：」——双重包装会让模型只看得到噪音，
                // 于是它自说自话（道友 2026-11-21 截图：思考内容外泄、后续不生成）。
                var sys: String? = null
                val history = StringBuilder()
                var lastUser = ""
                for (i in 0 until msgs.length()) {
                    val m = msgs.getJSONObject(i)
                    val role = m.optString("role", "user")
                    val content = m.optString("content", "")
                    when (role) {
                        "system" -> sys = if (sys.isNullOrBlank()) content else "$sys\n$content"
                        "assistant" -> history.append("A: ").append(content).append("\n")
                        else -> {
                            history.append("Q: ").append(content).append("\n")
                            lastUser = content
                        }
                    }
                }
                // 只有一轮对话：直接把原话交给模板
                val prompt = if (history.lines().count { it.isNotBlank() } <= 1) {
                    lastUser
                } else {
                    history.toString().trim()
                }
                // 思考开关已在原生层用 enable_thinking=false 关掉（ai_chat.cpp）。
                // 不要再在这里拼 /no_think——这版 Qwen3 模板里根本没这个关键字，
                // 拼上去只会变成 prompt 噪音（2026-11-21 读 GGUF 模板确认）。
                LlamaEngine.complete(prompt, sys)
            } catch (th: Throwable) {
                Log.e(TAG, "complete failed", th)
                CrashCatcher.stage("推理异常: ${th.message}")
                null
            }

            CrashCatcher.stage("推理完成")
            if (raw == null) return ""
            return extractText(raw)
        } catch (t: Throwable) {
            Log.e(TAG, "complete throw", t)
            CrashCatcher.stage("推理失败: ${t.message}")
            return ""
        } finally {
            busy.set(false)
        }
    }

    /**
     * 从引擎返回里取正文。
     *
     * Qwen3 是 thinking 模型，输出形如：
     *   <think>…一大段内部推理…</think>\n\n真正的回复
     * 思考过程绝不能展示给用户（道友 2026-11-21 截图反馈）。
     * 另外当生成被 max_tokens 截断时，</think> 可能根本还没出现，
     * 这时整段都是思考内容，正文为空 —— 宁可返回提示也不把思考当回复。
     */
    private fun extractText(raw: String): String {
        var s = raw.trim()
        // 不是 JSON 就直接当文本
        if (s.startsWith("{")) {
            s = try {
                val o = JSONObject(s)
                when {
                    o.has("response") -> o.optString("response")
                    o.has("text") -> o.optString("text")
                    o.has("content") -> o.optString("content")
                    else -> s
                }
            } catch (t: Throwable) { s }
        }
        return stripThinking(s)
    }

    /**
     * 剥掉 <think> 段。
     *
     * 思考已在原生层用 enable_thinking=false 关掉（ai_chat.cpp），
     * 这里是兵家副手：万一模型或模板行为变了，别把思维内容漏到界面上。
     *
     * 未闭合的处理变过：原来是整段丢。太狠了——若模型先写完正文
     * 再开 think（罕见但可能），正文也跟着没了。改成只丢掉未闭合的那个标签
     * 及其后面的内容，保留标签之前已经输出的东西。
     */
    private fun stripThinking(input: String): String {
        var s = input
        // 1. 完整的 <think>…</think>（含变体标签）全部删掉
        val closed = Regex("<\\s*(think|thinking|reasoning)\\s*>[\\s\\S]*?<\\s*/\\s*(think|thinking|reasoning)\\s*>",
                RegexOption.IGNORE_CASE)
        s = closed.replace(s, "")

        // 2. 还有未闭合的 <think> —— 后面的内容都不完整，只保留它之前的部分
        val openTag = Regex("<\\s*(think|thinking|reasoning)\\s*>", RegexOption.IGNORE_CASE)
        val m = openTag.find(s)
        if (m != null) {
            s = s.substring(0, m.range.first)
        }

        // 3. 残留的孤立闭合标签也去掉
        s = Regex("<\\s*/\\s*(think|thinking|reasoning)\\s*>", RegexOption.IGNORE_CASE).replace(s, "")

        return s.trim()
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
            // 名字从真实文件推导（见 ModelBridge.modelDisplayName）；拿不到就留空，UI 自己回退。
            JSONObject()
                .put("name", bridge.modelDisplayName())
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
