package com.xundao.yongwuzhijing

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * GatewayLauncher —— 在 App 进程内拉起内嵌的 OpenClaw 网关。
 *
 * 架构（2026-11-21 定，道友拍板 B 方案）：
 *   永无止境 App = 脑 + 手 + 面板，全部在一个包里。
 *   网关跑在 **本 App 自己的私有目录**，用 **自己的端口和 token**，
 *   与「寻道」（跑在 com.xundao.openclaw，端口 18789）完全隔离、互不干扰。
 *
 * 为什么能隔离（已实测 2026-11-21 16:16）：
 *   OpenClaw 支持以下环境变量，全部生效：
 *     OPENCLAW_HOME / OPENCLAW_STATE_DIR / OPENCLAW_CONFIG_PATH
 *     OPENCLAW_GATEWAY_PORT / OPENCLAW_ALLOW_MULTI_GATEWAY
 *   同时跑两个网关，18800 和 18789 都返回 200，日志里
 *   "gateway name conflict resolved" 证明系统自己处理了命名冲突。
 *
 * 为什么用 assets 而不是把 JS 塞进 lib：
 *   Android SELinux 只允许执行 APK 的 lib/ 目录，但 JS 只是**数据**，
 *   放在应用私有目录完全没问题。真正要可执行的只有 libnode.so（放在 jniLibs）。
 *
 * 原生库依赖（从 APK lib/arm64 提取，共 10 个 89MB）：
 *   libnode.so libicudata78 libicui18n78 libicuuc78 libcrypto3 libssl3
 *   libsqlite354 libcares libz13 libc++_shared
 * 必须在 files/lib 建同名软链，否则报 library "libz.so.1" not found。
 */
class GatewayLauncher(private val ctx: Context) {

    companion object {
        private const val TAG = "YwzjGw"

        /** 最近一次拉起的网关进程，给界面「网关」页读活没活 */
        @Volatile private var lastProc: Process? = null

        /** 进程还活着吗（纯看 PID，不探端口）——单例后界面用这个 */
        fun procAlive(): Boolean = try {
            INSTANCE?.proc?.isAlive == true
        } catch (t: Throwable) {
            false
        }

        /** 实例版（保留旧名，别的代码可能在用） */
        fun runningProcAlive(): Boolean = procAlive()

        /**
         * 内嵌网关端口。**不能是 18789（寻道在用）也不能是 18790（本地模型）。**
         */
        const val PORT = 18800

        /**
         * 本 App 内嵌网关的固定 token。
         *
         * 为什么写死而不用网关自己生成：网关自动生成的 token 只在日志里出现一次，
         * 而且我们这份配置如果生成得不对（2026-11-21 现场就是空的），App 侧根本读不到，
         * 只能带着空 key 去请求，网关一律 401。写死一个本机私密串，两边必然一致。
         * 只绑 loopback，不对外暴露。
         */
        const val TOKEN = "ywzj-3f9c41d8a7b04e25bb6e1d0c9f7a2c53"

        /**
         * 模型段（models + agents + auth）。没有它 /v1/chat/completions 必 500。
         *
         * apiKey 从 SharedPreferences 读（设置页填入），不写在源码里。
         */
        /** 设置页里存 API key 的 SharedPreferences 键名 */
        private const val PREF_KEY = "deepseek_key"

        /** 本地模型 id（离线、不花钱）。默认用这个。 */
        const val LOCAL_MODEL = "local/local-qwen3"
        /** 云端模型 id（设置页填了 API key 才可用）。 */
        const val CLOUD_MODEL = "deepseek/deepseek-chat"

        /** 当前选中的模型。默认本地；设置页勾了"用云端"且有 key 才切。 */
        fun currentModel(): String {
            val prefs = appCtx?.getSharedPreferences("ywzj_prefs", Context.MODE_PRIVATE)
            val useCloud = prefs?.getBoolean("use_cloud", false) ?: false
            return if (useCloud && apiKey().isNotBlank()) CLOUD_MODEL else LOCAL_MODEL
        }

        private val MODELS_JSON: String
            get() {
                val key = apiKey()
                return buildString {
                    append("{")
                    append("\"mode\":\"merge\",")
                    // 本地 provider：指向 App 自己的 llama.cpp（127.0.0.1:18790）。
                    // 不需要真 key，离线可用，是默认路径。
                    append("\"providers\":{")
                    append("\"local\":{")
                    append("\"baseUrl\":\"http://127.0.0.1:18790/v1\",")
                    append("\"api\":\"openai-completions\",")
                    append("\"apiKey\":\"local\",")
                    append("\"models\":[{")
                    append("\"id\":\"local-qwen3\",")
                    append("\"name\":\"本地模型 Qwen3\",")
                    append("\"reasoning\":false,")
                    append("\"input\":[\"text\"],")
                    append("\"contextWindow\":8192,")
                    append("\"maxTokens\":1024,")
                    append("\"api\":\"openai-completions\"")
                    append("}]},")
                    append("\"deepseek\":{")
                    append("\"baseUrl\":\"https://api.deepseek.com\",")
                    append("\"api\":\"openai-completions\",")
                    if (key.isNotBlank()) {
                        append("\"apiKey\":").append(org.json.JSONObject.quote(key)).append(",")
                    }
                    append("\"models\":[{")
                    append("\"id\":\"deepseek-chat\",")
                    append("\"name\":\"DeepSeek Chat\",")
                    append("\"reasoning\":false,")
                    append("\"input\":[\"text\",\"image\"],")
                    append("\"contextWindow\":131072,")
                    append("\"maxTokens\":8192,")
                    append("\"compat\":{\"supportsUsageInStreaming\":true},")
                    append("\"api\":\"openai-completions\"")
                    append("}]}}")
                    append("}")
                }
            }

        /** 读/写 API key。设置页调 setApiKey，写配置时自动用上。 */
        fun apiKey(): String = try {
            appCtx?.getSharedPreferences("ywzj_prefs", Context.MODE_PRIVATE)
                ?.getString(PREF_KEY, "").orEmpty()
        } catch (_: Throwable) {
            ""
        }

        fun setApiKey(key: String) {
            appCtx?.getSharedPreferences("ywzj_prefs", Context.MODE_PRIVATE)
                ?.edit()?.putString(PREF_KEY, key.trim())?.apply()
        }

        /** application context 快照 —— companion 里拿不到实例的 private ctx */
        @Volatile private var appCtx: Context? = null

        /** assets 里运行时目录名 */
        private const val ASSET_DIR = "openclaw"

        /** 标记文件：释放完成后写一个，下次跳过 */
        private const val STAMP = ".extracted-v2"

        /**
         * 跨页面单例。
         *
         * 为什么必须单例：2026-11-21 19:13 现场——MainActivity 在后台线程
         * `gw.start()` 跑完（释放 + 拉进程），而界面上的「网关」页读的是
         * 另一个 GatewayLauncher 实例的 proc → 永远显示「进程 停」。
         * 进程只能拉一份，这份状态就必须只有一份。
         */
        @Volatile private var INSTANCE: GatewayLauncher? = null

        fun get(ctx: Context): GatewayLauncher =
            INSTANCE ?: synchronized(this) {
                appCtx = ctx.applicationContext
                INSTANCE ?: GatewayLauncher(ctx.applicationContext).also { INSTANCE = it }
            }
    }

    /**
         * DeepSeek API key 存储位置。
         *
         * 为什么不硬编码进源码：源码进 git、APK 会被分发，key 写死在里面等于公开。
         * 所以放在 App 私有 SharedPreferences（`ywzj_prefs/deepseek_key`），
         * 由「设置」页首次填写，GatewayLauncher 每次写配置时读取。
         * 没有 key 时 models 段不写 apiKey —— 网关会返回可读的配置错误，
         * 而不是静默 500。这比「造假 key 装上看起来正常」诚实。
         */
    private val root: File get() = File(ctx.filesDir, ASSET_DIR)
    private val home: File get() = File(root, "home")
    private val state: File get() = File(root, "state")
    private val libDir: File get() = File(ctx.filesDir, "lib")
    private val configFile: File get() = File(home, "openclaw.json")

    @Volatile private var proc: Process? = null
    @Volatile private var lastError: String = ""

    fun lastError(): String = lastError

    /** 给界面看的：启动是否真发起过、跑到哪一步、进程 pid */
    fun diag(): String {
        val steps = startSteps.joinToString(" > ")
        val p = proc
        val rt = File(root, "runtime")
        val count = try { rt.walkTopDown().count { it.isFile } } catch (_: Throwable) { -1 }
        // 【2026-11-24】pid 显示为「-」时，用户无法区分
        // 「我拉起的进程」和「上次留下的孤儿」。这里把两个都列出来。
        val myPid = if (p != null && p.isAlive) pidOf(p) else "-"
        val occupied = if (portBusy()) "是" else "否"
        return "steps=[" + steps + "] pid=" + myPid +
                " 端口占用=" + occupied +
                " files=" + count +
                (if (lastError.isBlank()) "" else " err=" + lastError)
    }

    /** 启动过程每一步都记下来，界面直接显示，不用拔手机看日志 */
    @Volatile private var startSteps: MutableList<String> = java.util.Collections.synchronizedList(ArrayList())

    /**
     * 网关是否在跑。
     *
     * 【2026-11-25 根因】旧实现 `val p = proc ?: return false` 有两个致命问题：
     *
     * 1. Activity 重建（切后台回来 / 旋屏 / 系统回收）时，proc 字段还在（companion 静态），
     *    但如果是 App 进程被重建，proc 是 null → 返回 false → MainActivity.onCreate 又调
     *    start() → 又 spawn 一个网关 → 端口被占 → 新的失败退出、旧的活着。
     *    日志实证：00:48:16.563 Gateway failed to start: another gateway instance is
     *    already listening on ws://127.0.0.1:18800
     *
     * 2. 反过来，proc 活着但网关内部已崩（进程没退、HTTP 全 500）时，probePort() 仍可能
     *    因为健康检查返回 200 而判「在跑」。
     *
     * 现在：端口能应答就是「在跑」，不管 proc 对象在不在。proc 只用来做日志显示。
     * 进程真的死了 → 端口自然断 → 自然返 false，逻辑依然自洽。
     */
    fun isRunning(): Boolean {
        val p = proc
        if (p != null && p.isAlive && probePort()) return true
        // proc 为 null 或进程已退，但端口还有人应答 → 说明网关在跑（只是我们丢了句柄），
        // 绝不能当「没跑」去再 spawn 一个，那必然撞端口。
        return probePort()
    }

    /**
     * 端口上还有别人占着吗（不管是不是本 App 的 proc 对象）。
     *
     * 【2026-11-24 根因】日志实证：
     *   Gateway failed to start: another gateway instance is already listening on ws://127.0.0.1:18800
     *   Port 18800 is already in use.
     * App 重启后 proc 字段为 null → isRunning() 返 false → 走完整启动 →
     * 但旧进程根本没死，还占着 18800 → 新进程绑不上端口就退出 →
     * 而旧网关用的是旧配置（没有 provider）→ 每次对话都是 500。
     */
    private fun portBusy(): Boolean {
        return try {
            val s = java.net.Socket()
            s.connect(java.net.InetSocketAddress("127.0.0.1", PORT), 1200)
            s.close()
            true
        } catch (t: Throwable) { false }
    }

    /**
     * 杀掉占用 PORT 的本地进程。
     *
     * Android 没有 lsof，也不能用 pm 跨进程（见 TOOLS.md F28）。
     * 走的足系统自带的 /proc/net/tcp：它列出所有监听 socket（十六进制端口 + inode），
     * 再扫 /proc 下每个 pid 的 fd 目录比对 inode。只能看到本 uid 的进程 —— 而孤儿子刚就是本 uid。
     * 扫不到就退而求其次：把 pid 文件/自身 spawn 的进程全杀一遍。
     */
    private fun killByPort() {
        val killed = mutableListOf<String>()
        try {
            // ---- 第一路（最可靠）：本实例 spawn 过的进程对象，直接强杀 ----
            // App 重启后 proc 为 null，这一路失效，所以要下面两路兜底。
            try {
                val p = proc
                if (p != null) { killed.add(pidOf(p) + "(proc)"); p.destroyForcibly() }
            } catch (t: Throwable) {}
            proc = null

            // ---- 第二路：扫 /proc 找同 uid 的 node/openclaw 进程 ----
            // Android 上 /proc/<pid>/fd 对同 uid 也可能读不到，所以先按 cmdline 判断，不看 fd。
            // uid 相同 + 命令行含 openclaw/libnode → 就是我们的孤儿网关。
            val myUid = android.os.Process.myUid()
            val myPid = android.os.Process.myPid()
            val pids = java.io.File("/proc").list() ?: emptyArray()
            for (d in pids) {
                val pid = d.toIntOrNull() ?: continue
                if (pid == myPid) continue
                val uid = try {
                    java.io.File("/proc/" + d + "/status").useLines { l ->
                        l.firstOrNull { it.startsWith("Uid:") }
                            ?.removePrefix("Uid:")?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.toIntOrNull()
                    }
                } catch (t: Throwable) { null }
                if (uid != myUid) continue
                val cmd = try {
                    java.io.File("/proc/" + d + "/cmdline").readText().replace('\u0000', ' ')
                } catch (t: Throwable) { "" }
                if (!cmd.contains("openclaw") && !cmd.contains("libnode")) continue
                android.os.Process.sendSignal(pid, 9)
                killed.add(pid.toString() + "(cmdline)")
            }

            Boot.trace(ctx, "killByPort: 已杀 " + killed)
        } catch (t: Throwable) {
            Boot.trace(ctx, "killByPort 失败: " + t.javaClass.simpleName + ": " + t.message)
        }
    }

    /**
     * 重写配置（设置页改了 API key 后调）。
     * 不重启进程 —— 配置只在网关启动时读，所以界面要提示用户点「重启网关」。
     */
    fun rewriteConfig(): Boolean = writeDefaultConfig()

    /**
     * 启动网关。幂等：已在跑就直接返回 true。
     * 同步执行，调用方应该在后台线程调（首次要释放 400MB assets，可能几十秒）。
     */
    fun start(): Boolean {
        startSteps.clear()
        if (isRunning()) { startSteps.add("已运行"); return true }

        // 【2026-11-24】端口被占 + 不是本实例的 proc = 孤儿网关（旧配置）。
        // 必须清掉，否则新进程绑不上端口，而请求被哑网关接走 → 永远 500。
        if (portBusy()) {
            startSteps.add("清理旧网关")
            killByPort()
            var waited = 0
            while (portBusy() && waited < 8000) {
                Thread.sleep(200); waited += 200
            }
            if (portBusy()) {
                lastError = "端口 $PORT 被占，清理失败"
                return false
            }
            startSteps.add("旧网关已清")
        }

        if (!extractAssets()) return false
        startSteps.add("assets ok")
        if (!ensureSymlinks()) return false
        startSteps.add("libs ok")
        if (!writeDefaultConfig()) return false
        startSteps.add("config ok")
        // 把配置路径交给 AgentClient，让它能判断「网关到底能不能回答」
        // 没这一步 probe() 无法区分「网关在跑」和「网关能用」
        AgentClient.bindConfig(configFile)
        if (!spawn()) return false
        startSteps.add("spawn ok")
        // 【2026-11-22 根因修复】网关进程跑起来之后，必须拉前台服务 + 唤醒锁，
        // 否则锁屏即冻结 / 被省电策略杀掉。这一步失败不算启动失败（网关仍在跑），
        // 但会记日志，方便排查。
        try {
            GatewayService.start(ctx)
            startSteps.add("foreground ok")
        } catch (t: Throwable) {
            Boot.trace(ctx, "前台服务启动失败: ${t.javaClass.simpleName}: ${t.message}")
        }
        return true
    }

    /**
     * 申请电池优化白名单（华为/小米等国产 ROM 不申请就一定会杀后台）。
     * 这是系统弹窗，用户可能拒绝，所以不阻塞启动流程。
     */
    fun requestIgnoreBatteryOptimizations(activity: android.app.Activity) {
        try {
            val pm = activity.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
            if (pm.isIgnoringBatteryOptimizations(activity.packageName)) {
                Boot.trace(activity, "已在电池优化白名单")
                return
            }
            val i = android.content.Intent(
                android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
            )
            i.data = android.net.Uri.parse("package:" + activity.packageName)
            activity.startActivity(i)
        } catch (t: Throwable) {
            Boot.trace(activity, "申请电池白名单失败: ${t.message}")
        }
    }

    fun stop() {
        try {
            proc?.destroy()
            proc?.waitFor(5, TimeUnit.SECONDS)
            proc?.destroyForcibly()
        } catch (_: Throwable) {
        }
        proc = null
        try { GatewayService.stop(ctx) } catch (_: Throwable) {}
    }

    // ---------- 内部 ----------

    /**
     * 把 assets/openclaw 整个释放到 filesDir/openclaw。
     *
     * 为什么必须释放：node 要从文件系统读 JS，而 assets 在 APK 内部是压缩包，
     * 没法当普通目录用（除非整个 APK 都不压缩，那会让 APK 大很多）。
     *
     * 幂等：有 STAMP 且目录非空就跳过。
     */
    private fun extractAssets(): Boolean {
        return try {
            val stamp = File(root, STAMP)
            // 幂等：有 stamp 且清单比对通过就跳过。
            // 不能只看“关键文件在不在”—— 2026-11-22 就是那样漏掉的：
            // 51351 个文件里少释放了 4466 个，而 checked 的四个文件恰好在前面。
            if (stamp.exists() && verifyManifest().isEmpty()) {
                return true
            }
            // 旧版本释放出的 runtime 可能是残缺/串版的（看得到 manifest 比对不出来，
            // 因为旧释放根本没写全）。新 STAMP 不存在时一律重来：删掉整个 runtime 目录。
            File(root, "runtime").deleteRecursively()
            root.mkdirs()

            val startedAt = System.currentTimeMillis()
            val files = extractByManifest(startedAt)
            val secs = (System.currentTimeMillis() - startedAt) / 1000
            Boot.trace(ctx, "网关资源释放：$files 文件 / ${secs}s")

            if (files < 1000) {
                lastError = "清单读取失败或过短（$files），无法释放"
                Boot.trace(ctx, lastError)
                return false
            }

            // 逐条比对：这是唯一的验收标准。缺一个就删 stamp 重试。
            val lacking = verifyManifest()
            if (lacking.isNotEmpty()) {
                lastError = "释放后缺 ${lacking.size} 个文件，例如: " +
                        lacking.take(3).joinToString(", ") { it.substringAfterLast('/') }
                Boot.trace(ctx, lastError)
                Boot.trace(ctx, "缺失样本: " + lacking.take(8).joinToString(" | "))
                if (stamp.exists()) stamp.delete()
                return false
            }
            stamp.writeText(System.currentTimeMillis().toString())
            true
        } catch (t: Throwable) {
            lastError = "释放失败: ${t.javaClass.simpleName}: ${t.message}"
            Boot.trace(ctx, lastError)
            false
        }
    }

    /**
     * 按 assets/openclaw/manifest.txt 逐条释放。
     *
     * 为什么不再用递归 AssetManager.list()：
     *   2026-11-22 现场：APK 里 51351 个文件，App 只释了 46885 个，
     *   但递归函数返回「成功」。list() 对深层/大量子目录不可靠（某些 ROM
     *   会返回空数组，被我们当成「这是文件」然后 open() 失败又被吞掉）。
     *   改成一个扁平清单，逐条 open()，错哪一条一眼可见、可重试。
     *
     * 顺序：把大文件放最后的风险没有，因为清单是按路径 sort 的；
     * 重要的是每条都真的去写、失败就记，不许静默跳过。
     */
    private fun extractByManifest(startedAt: Long): Int {
        val am = ctx.assets
        val manifest: List<String> = try {
            am.open("$ASSET_DIR/manifest.txt").bufferedReader().use { it.readLines() }
        } catch (t: Throwable) {
            // 清单缺失（旧包）：退回递归方式，但这条路已知不可靠，只作为兼容
            Boot.trace(ctx, "清单不存在，回退递归释放: ${t.message}")
            return 0
        }

        var files = 0
        val failed = ArrayList<String>()
        for (rel in manifest) {
            if (rel.isBlank()) continue
            val dest = File(root, "runtime/$rel")
            // 【2026-11-22】不能用 length()>0 判断“已释放”：零字节文件是合法的，
            // 旧写法会让它们每次启动都重写一遍。只看存在且是普通文件。
            if (dest.isFile) { files++; continue }
            try {
                am.open("$ASSET_DIR/runtime/$rel").use { ins ->
                    dest.parentFile?.mkdirs()
                    FileOutputStream(dest).use { out -> ins.copyTo(out, 65536) }
                }
                files++
            } catch (t: Throwable) {
                failed.add(rel + " :: " + t.javaClass.simpleName)
            }
            if (files > 0 && files % 5000 == 0) {
                Boot.trace(ctx, "释放中… $files / ${manifest.size}  ${(System.currentTimeMillis() - startedAt) / 1000}s")
            }
        }
        if (failed.isNotEmpty()) {
            Boot.trace(ctx, "释放失败 ${failed.size} 条: " + failed.take(5).joinToString(" | "))
        }
        return files
    }

    /**
     * 按清单逐条检查文件是否真的落地了。返回缺失列表（空 = 完好）。
     *
     * 【2026-11-22 修正】旧判据 `f.length() == 0L` 是错的：
     * npm 包里合法存在零字节文件（占位 .js / 空 index 等），
     * 当前运行时里正好有 23 个，导致释放完全成功却报“缺 23 个文件”。
     * 正确判据：文件存在且是普通文件 即可。内容由 APK 的 CRC 保证，
     * 释放阶段 copyTo 已保证字节一致，不需要用“长度 > 0”当代理。
     */
    private fun verifyManifest(): List<String> {
        return try {
            val lines = ctx.assets.open("$ASSET_DIR/manifest.txt")
                .bufferedReader().use { it.readLines() }
            val bad = ArrayList<String>()
            for (rel in lines) {
                if (rel.isBlank()) continue
                val f = File(root, "runtime/$rel")
                if (!f.isFile) bad.add(rel)
            }
            bad
        } catch (t: Throwable) {
            // 读不到清单：退回只查关键文件（兼容旧包）
            val must = listOf(
                "runtime/openclaw.mjs",
                "runtime/dist/entry.js",
                "runtime/dist/cli/daemon-cli.js",
                "runtime/package.json"
            )
            must.filter { !File(root, it).exists() }
        }
    }

    /**
     * 确保原生库能被链接器找到。
     *
     * 两种来源，两种命名：
     *   A. Termux 包（当前用这个）：库名就是标准名 libz.so.1 / libcrypto.so.3，
     *      直接拷进 jniLibs 即可，**不需要软链**。
     *   B. 从另一份 APK 提取的：文件名带版本后缀（libz13.so），要建软链。
     *
     * 这里做成「缺什么补什么」：先看标准名在不在，在就直接过；
     * 不在就找带后缀的版本建软链。
     */
    private fun ensureSymlinks(): Boolean {
        val nativeDir = File(ctx.applicationInfo.nativeLibraryDir)
        if (!nativeDir.isDirectory) {
            lastError = "nativeLibraryDir 不存在: $nativeDir"
            return false
        }
        val available = nativeDir.list()?.toSet() ?: emptySet()

        // 标准名 → 需要的实际文件（前缀匹配，见下）
        //
        // 为什么用前缀而不是写死名字：CI 把 Termux 的多级版本号改名成
        // lib<name>_<版本>.so（如 libsqlite3.so.3.54.1 → libsqlite3_3_54_1.so）。
        // 写死候选名单已经错过一次（2026-11-21：CI 当时叫 libsqlite33.so，
        // 候选只有 libsqlite354.so，于是永远找不到）。
        // 现在：每个标准名给一组前缀，在 nativeDir 里找第一个命中项。
        val needs = listOf(
            "libcrypto.so.3" to listOf("libcrypto3", "libcrypto_", "libcrypto.so"),
            "libssl.so.3" to listOf("libssl3", "libssl_", "libssl.so"),
            "libz.so.1" to listOf("libz1", "libz_", "libz.so"),
            "libsqlite3.so" to listOf("libsqlite3_", "libsqlite3.so", "libsqlite33", "libsqlite354"),
            "libicuuc.so.79" to listOf("libicuuc_", "libicuuc79", "libicuuc78", "libicuuc.so"),
            "libicui18n.so.79" to listOf("libicui18n_", "libicui18n79", "libicui18n78", "libicui18n.so"),
            "libicudata.so.79" to listOf("libicudata_", "libicudata79", "libicudata78", "libicudata.so"),
            "libcares.so" to listOf("libcares"),
            "libc++_shared.so" to listOf("libc++_shared")
        )

        /**
         * 在 nativeDir 里按前缀找 .so。
         * 先精确匹配前缀本身，再匹配 `<前缀>_xxx.so` / `<前缀><数字>.so`。
         */
        fun findLib(prefix: String): File? {
            val direct = File(nativeDir, if (prefix.endsWith(".so")) prefix else "$prefix.so")
            if (direct.exists()) return direct
            val hit = available.filter { it.startsWith(prefix) && it.endsWith(".so") }
                .sortedBy { it.length }
                .firstOrNull()
            return if (hit == null) null else File(nativeDir, hit)
        }

        return try {
            libDir.mkdirs()
            for ((standard, prefixes) in needs) {
                val dst = File(libDir, standard)
                if (dst.exists()) continue
                // 标准名已能直接用（包括未改名的库），跳过
                if (File(nativeDir, standard).exists()) continue

                val src = prefixes.asSequence().mapNotNull { findLib(it) }.firstOrNull()
                if (src == null) {
                    lastError = "缺原生库: $standard（在 $nativeDir 找不到前缀 ${prefixes.joinToString()}）"
                    Boot.trace(ctx, lastError)
                    return false
                }
                try {
                    java.nio.file.Files.createSymbolicLink(dst.toPath(), src.toPath())
                    Boot.trace(ctx, "软链 $standard -> ${src.name}")
                } catch (_: Throwable) {
                    src.copyTo(dst, overwrite = true)
                    Boot.trace(ctx, "拷贝 $standard <- ${src.name}")
                }
            }
            true
        } catch (t: Throwable) {
            lastError = "准备原生库失败: ${t.message}"
            Boot.trace(ctx, lastError)
            false
        }
    }

    /**
     * 写默认配置。已存在就不动（保留用户改动）。
     *
     * 关键：端口 18800，绑 loopback，和寻道的 18789 完全分开。
     *
     * 【2026-11-24 补 models 段 —— 这是「测试对话 500」的真正根因】
     * 之前这里只写 gateway + session，**完全没有 models / providers / agents**。
     * 网关能起来、/health 200、端口连通，但一旦 POST /v1/chat/completions，
     * 它找不到任何可用的模型 provider → 内部抛错 → 500（119ms 就返回）。
     * 表现是「链路全绿，唯独不能说话」，我因此白排查了一轮。
     * 判据：配置里没有 models.providers 和 agents.defaults.model，就不算配置完成。
     */
    private fun writeDefaultConfig(): Boolean {
        return try {
            home.mkdirs()
            state.mkdirs()

            // 旧版生成的配置只有 bind/port/mode，缺 auth 和 OpenAI 端点开关。
            // 不能因为「文件已存在」就整份跳过，否则装过旧包的手机会一直带着坏配置。
            val desired = buildString {
                append("{\n")
                append("  \"gateway\": {\n")
                append("    \"mode\": \"local\",\n")
                append("    \"bind\": \"loopback\",\n")
                append("    \"port\": ").append(PORT).append(",\n")
                append("    \"auth\": { \"mode\": \"token\", \"token\": \"").append(TOKEN).append("\" },\n")
                append("    \"http\": { \"endpoints\": { \"chatCompletions\": { \"enabled\": true } } }\n")
                append("  },\n")
                append("  \"session\": { \"dmScope\": \"per-channel-peer\" },\n")
                append("  \"models\": ").append(MODELS_JSON).append(",\n")
                append("  \"agents\": { \"defaults\": { \"model\": { \"primary\": \"").append(currentModel()).append("\" } } },\n")
                append("  \"auth\": { \"profiles\": { \"deepseek:default\": { \"provider\": \"deepseek\", \"mode\": \"api_key\" } } }\n")
                append("}\n")
            }

            val existing = if (configFile.exists()) configFile.readText() else ""
            // 幂等判据必须包含 apiKey 状态，否则「先写配置、后填 key」会永远缺 key
            val key = apiKey()
            val keyOk = if (key.isBlank()) !existing.contains("apiKey") else existing.contains(key)
            // 四个必须项：token 对得上、chatCompletions 开了、端口对、模型段在
            val ok = existing.contains(TOKEN) &&
                    existing.contains("chatCompletions") &&
                    existing.contains("\"port\": $PORT") &&
                    existing.contains("\"providers\"") &&
                    existing.contains(currentModel()) &&
                    keyOk
            if (ok) return true

            // 保留用户可能改过的其他字段：只要旧文件能解析，就把 gateway 段覆盖掉，其余不动
            val merged = try {
                if (existing.isNotBlank()) {
                    val root = org.json.JSONObject(existing)
                    val gw = org.json.JSONObject()
                        .put("mode", "local")
                        .put("bind", "loopback")
                        .put("port", PORT)
                        .put("auth", org.json.JSONObject().put("mode", "token").put("token", TOKEN))
                        .put(
                            "http", org.json.JSONObject().put(
                                "endpoints", org.json.JSONObject().put(
                                    "chatCompletions", org.json.JSONObject().put("enabled", true)
                                )
                            )
                        )
                    root.put("gateway", gw)
                    if (!root.has("session")) {
                        root.put("session", org.json.JSONObject().put("dmScope", "per-channel-peer"))
                    }
                    // 模型段整段覆盖：没有它，chat/completions 必 500
                    root.put("models", org.json.JSONObject(MODELS_JSON))
                    val ag = root.optJSONObject("agents") ?: org.json.JSONObject()
                    val agd = ag.optJSONObject("defaults") ?: org.json.JSONObject()
                    agd.put("model", org.json.JSONObject().put("primary", currentModel()))
                    ag.put("defaults", agd)
                    root.put("agents", ag)
                    val au = root.optJSONObject("auth") ?: org.json.JSONObject()
                    val aup = au.optJSONObject("profiles") ?: org.json.JSONObject()
                    aup.put(
                        "deepseek:default",
                        org.json.JSONObject().put("provider", "deepseek").put("mode", "api_key")
                    )
                    au.put("profiles", aup)
                    root.put("auth", au)
                    root.toString(2)
                } else desired
            } catch (_: Throwable) {
                desired
            }
            configFile.writeText(merged)
            Boot.trace(ctx, "网关配置已写入（token + chatCompletions + 模型段）")
            true
        } catch (t: Throwable) {
            lastError = "写配置失败: ${t.message}"
            false
        }
    }

    /** 真正拉起进程 */
    private fun spawn(): Boolean {
        return try {
            val nativeDir = ctx.applicationInfo.nativeLibraryDir
            val node = File(nativeDir, "libnode.so")
            if (!node.exists()) {
                lastError = "libnode.so 不在 $nativeDir"
                return false
            }
            val entry = File(root, "runtime/openclaw.mjs")
            if (!entry.exists()) {
                lastError = "openclaw.mjs 不在 ${entry.absolutePath}"
                return false
            }

            // 启动前自检：把 node 模块解析依赖的两个关键包实际情况写进日志。
            // 2026-11-21 报错「Cannot find package .../typebox/index.js imported from pi-ai/dist/index.js」
            // —— 这是 exports 没生效、退回 main 的表现。得看到真实的目录结构才能定位。
            runDiag(File(root, "runtime"))

            val pb = ProcessBuilder(
                node.absolutePath,
                entry.absolutePath,
                "gateway",
                "--port", PORT.toString()
            )
            pb.directory(File(root, "runtime"))
            val env = pb.environment()
            env["LD_LIBRARY_PATH"] = "${libDir.absolutePath}:$nativeDir"
            env["OPENCLAW_HOME"] = home.absolutePath
            env["OPENCLAW_STATE_DIR"] = state.absolutePath
            env["OPENCLAW_CONFIG_PATH"] = configFile.absolutePath
            env["OPENCLAW_GATEWAY_PORT"] = PORT.toString()
            env["OPENCLAW_ALLOW_MULTI_GATEWAY"] = "1"
            env["HOME"] = home.absolutePath
            env["TMPDIR"] = ctx.cacheDir.absolutePath
            // 不设 PATH 时部分子进程查找命令会失败；给一个最小 PATH
            if (env["PATH"] == null) env["PATH"] = "/system/bin:/system/xbin"
            // 【2026-11-23 自诊断】spawn 之前先确认 node 自己能不能起来。
            // 之前所有失败都表现为「40 秒端口不通」，真实 stderr 全在 gateway.log 里、
            // 界面看不到，据此排查连续误判 8 轮。现在把 node 的版本/错误直接摆到界面上。
            val selfCheck = nodeSelfCheck(node)
            startSteps.add(selfCheck)
            Boot.trace(ctx, "node 自检: $selfCheck")
            if (!selfCheck.startsWith("node ok")) {
                lastError = "node 自检失败: $selfCheck"
                Boot.trace(ctx, lastError)
                return false
            }

            pb.redirectErrorStream(true)
            val logFile = File(root, "gateway.log")
            val logStart = try { logFile.length() } catch (_: Throwable) { 0L }
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
            val p = pb.start()
            proc = p
            startSteps.add("spawned")
            Boot.trace(ctx, "网关进程已启动 pid=" + pidOf(p))

            // 等最多 40 秒看端口是否起来
            val ok = waitPort(40_000)
            if (!ok) {
                // 【2026-11-23 自诊断】把这次启动新写入的日志尾部捞进界面。
                // 「端口不通」只是症状，node 打的那几行才是根因。
                val died = try { !p.isAlive } catch (_: Throwable) { false }
                val tail = tailOf(logFile, logStart, 25)
                lastError = (if (died) "进程已退出" else "端口超时") + " | node 输出: " + tail
                startSteps.add(if (died) "进程已退出" else "端口超时")
                Boot.trace(ctx, lastError)
                return false
            }

            // 【2026-11-24 根因】端口通 ≠ 我拉起的这个进程活着。
            // 旧版只要 waitPort 过了就报「已启动」，可实际是新进程绑不上端口后
            // 立刻退出、旧网关还在应答 —— 界面于是显示「运行正常」但每次对话都 500。
            // 这里必须验：① 我拉起的进程没有退出；② 它真的能回答（而不是只开了端口）。
            Thread.sleep(1500)   // 给它一点时间，若绑端口失败就会在这期间退出
            val aliveAfter = try { p.isAlive } catch (_: Throwable) { false }
            if (!aliveAfter) {
                val tail = tailOf(logFile, logStart, 25)
                lastError = "新进程启动后立即退出（端口被占或配置错）| node 输出: " + tail
                startSteps.add("新进程已退出")
                Boot.trace(ctx, lastError)
                return false
            }
            startSteps.add("port ok")
            true
        } catch (t: Throwable) {
            lastError = "启动失败: ${t.javaClass.simpleName}: ${t.message}"
            Boot.trace(ctx, lastError)
            false
        }
    }

    /**
     * 启动前自检：只查 node 模块解析真正用到的东西，输出写进 gateway.log。
     *
     * 为什么要有这个：报错信息只有一行「Cannot find package X imported from Y」，
     * 看不出是文件缺失、还是 package.json 坏了导致 exports 没生效。
     * 这里把事实摆出来：文件在不在、package.json 能不能解析、exports 指的文件在不在。
     */
    private fun runDiag(rt: File) {
        try {
            val sb = StringBuilder("===== diag ${java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())} =====\n")
            val targets = listOf(
                "node_modules/@sinclair/typebox",
                "node_modules/@mariozechner/pi-ai"
            )
            for (rel in targets) {
                val d = File(rt, rel)
                sb.append("--- $rel exists=${d.exists()} ---\n")
                if (!d.exists()) continue
                val entries = d.list()?.sorted() ?: emptyList()
                sb.append("条目: " + entries.take(25).joinToString(", ") + "\n")
                val pkg = File(d, "package.json")
                sb.append("package.json exists=${pkg.exists()} size=${if (pkg.exists()) pkg.length() else -1}\n")
                if (pkg.exists()) {
                    val txt = pkg.readText()
                    sb.append("前 120 字符: " + txt.take(120).replace("\n", " ") + "\n")
                    val hasExports = txt.contains("\"exports\"")
                    sb.append("含 exports 字段=$hasExports\n")
                    val idx = txt.indexOf("\"main\"")
                    if (idx >= 0) sb.append("main: " + txt.substring(idx, minOf(idx + 60, txt.length)).replace("\n", " ") + "\n")
                }
            }
            // typebox 的 exports 指向 build/esm/index.mjs，必须存在
            for (p in listOf(
                "node_modules/@sinclair/typebox/build/esm/index.mjs",
                "node_modules/@sinclair/typebox/build/cjs/index.js",
                "node_modules/@mariozechner/pi-ai/dist/index.js"
            )) {
                val f = File(rt, p)
                sb.append("$p exists=${f.exists()} size=${if (f.exists()) f.length() else -1}\n")
            }
            File(root, "gateway.log").appendText(sb.toString())
        } catch (t: Throwable) {
            Boot.trace(ctx, "diag 失败: ${t.message}")
        }
    }

    /**
     * 【2026-11-23 新增】node 自检：真把 libnode.so 跑起来，报告版本或错误。
     *
     * 为什么必须做：App 之前只判断「40 秒端口不通」，node 是死了还是慢了、
     * 缺库还是语法错，全都看不见。8 轮排查里有 6 轮是在猜这个。
     * 这里用和 spawn 完全相同的 LD_LIBRARY_PATH 跑一句最简单的 JS，
     * 能跑通 → node 和它的动态库都没问题；跑不通 → 把真实 stderr 交出去。
     */
    private fun nodeSelfCheck(node: File): String {
        return try {
            val nativeDir = ctx.applicationInfo.nativeLibraryDir
            val pb = ProcessBuilder(node.absolutePath, "-e", "process.stdout.write(process.version)")
            pb.redirectErrorStream(true)
            val env = pb.environment()
            env["LD_LIBRARY_PATH"] = "${libDir.absolutePath}:$nativeDir"
            env["HOME"] = home.absolutePath
            env["TMPDIR"] = ctx.cacheDir.absolutePath
            if (env["PATH"] == null) env["PATH"] = "/system/bin:/system/xbin"
            val p = pb.start()
            val done = p.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)
            if (!done) { try { p.destroyForcibly() } catch (_: Throwable) { }; return "node 超时(15s)" }
            val out = p.inputStream.bufferedReader().use { it.readText() }.trim()
            if (p.exitValue() == 0 && out.startsWith("v")) "node ok $out"
            else "exit=${p.exitValue()} ".plus(out.replace("\n", " ").take(400))
        } catch (t: Throwable) {
            "异常 ${t.javaClass.simpleName}: ${t.message}"
        }
    }

    /** 读日志文件从 fromOffset 开始的新内容，取最后 n 行，压成一行给界面看 */
    private fun tailOf(f: File, fromOffset: Long, n: Int): String {
        return try {
            if (!f.isFile) return "(无日志)"
            val len = f.length()
            if (len <= fromOffset) return "(本次无输出)"
            val start = maxOf(fromOffset, len - 64 * 1024)
            val bytes = ByteArray((len - start).toInt())
            java.io.RandomAccessFile(f, "r").use { raf ->
                raf.seek(start)
                raf.readFully(bytes)
            }
            val text = String(bytes, Charsets.UTF_8)
                .replace(Regex("[\\u001B][\\[][0-9;]*m"), "")
            val lines = text.split('\n').filter { it.isNotBlank() }
            if (lines.isEmpty()) return "(本次无输出)"
            lines.takeLast(n).joinToString(" ⏎ ").take(900)
        } catch (t: Throwable) {
            "(读日志失败: ${t.message})"
        }
    }

    /**
     * 取子进程 pid。
     *
     * 【2026-11-25 踩坑】p.pid() 编译不过：Process.pid() 是 API 26 加的**默认方法**，
     * Kotlin 对 java.lang.Process 的映射看不到它 → Unresolved reference: pid。
     * 走 ProcessHandle（同样 API 26，但走 android.jar 的 type-use 映射能解析）。
     * 仍失败就反射兜底，最后返回 "?"。
     */
    private fun pidOf(p: Process): String {
        try {
            val h = p.toHandle()
            return h.pid().toString()
        } catch (_: Throwable) {}
        try {
            val f = Process::class.java.getDeclaredField("pid")
            f.isAccessible = true
            return f.get(p).toString()
        } catch (_: Throwable) {}
        return "?"
    }

    /** 探测本地端口：真发 HTTP GET /health 并读状态码 */
    private fun probePort(): Boolean {
        return try {
            java.net.Socket().use { s ->
                s.connect(java.net.InetSocketAddress("127.0.0.1", PORT), 1500)
                s.soTimeout = 2000
                s.getOutputStream().apply {
                    write("GET /health HTTP/1.1\r\nHost: 127.0.0.1:$PORT\r\nConnection: close\r\n\r\n".toByteArray())
                    flush()
                }
                val buf = ByteArray(128)
                val n = s.getInputStream().read(buf)
                n > 0 && String(buf, 0, n, Charsets.ISO_8859_1).startsWith("HTTP/1.1 200")
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun waitPort(ms: Int): Boolean {
        val deadline = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < deadline) {
            if (probePort()) return true
            Thread.sleep(800)
        }
        return false
    }
}
