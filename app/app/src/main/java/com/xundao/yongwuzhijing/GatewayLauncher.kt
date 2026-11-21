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

        /** 进程还活着吗（不探端口，纯看 PID 活没活） */
        fun runningProcAlive(): Boolean = try {
            lastProc?.isAlive == true
        } catch (t: Throwable) {
            false
        }

        /** 实例版的「进程活没活」（单例后界面就该用这个） */
        fun isProcAlive(): Boolean = try {
            proc?.isAlive == true
        } catch (t: Throwable) {
            false
        }

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

        /** assets 里运行时目录名 */
        private const val ASSET_DIR = "openclaw"

        /** 标记文件：释放完成后写一个，下次跳过 */
        private const val STAMP = ".extracted-v1"

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
                INSTANCE ?: GatewayLauncher(ctx.applicationContext).also { INSTANCE = it }
            }
    }

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
        return "steps=[" + steps + "] pid=" + (if (p != null && p.isAlive) pidOf(p) else "-") +
                (if (lastError.isBlank()) "" else " err=" + lastError)
    }

    /** 启动过程每一步都记下来，界面直接显示，不用拔手机看日志 */
    @Volatile private var startSteps: MutableList<String> = java.util.Collections.synchronizedList(ArrayList())

    /** 网关是否在跑（进程活着 + 端口能连） */
    fun isRunning(): Boolean {
        val p = proc ?: return false
        if (!p.isAlive) return false
        return probePort()
    }

    /**
     * 启动网关。幂等：已在跑就直接返回 true。
     * 同步执行，调用方应该在后台线程调（首次要释放 400MB assets，可能几十秒）。
     */
    fun start(): Boolean {
        startSteps.clear()
        if (isRunning()) { startSteps.add("已运行"); return true }
        if (!extractAssets()) return false
        startSteps.add("assets ok")
        if (!ensureSymlinks()) return false
        startSteps.add("libs ok")
        if (!writeDefaultConfig()) return false
        startSteps.add("config ok")
        return spawn()
    }

    fun stop() {
        try {
            proc?.destroy()
            proc?.waitFor(5, TimeUnit.SECONDS)
            proc?.destroyForcibly()
        } catch (_: Throwable) {
        }
        proc = null
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
            if (stamp.exists() && File(root, "runtime/openclaw.mjs").exists()) {
                return true
            }
            root.mkdirs()

            val am = ctx.assets
            val top = am.list(ASSET_DIR) ?: run {
                lastError = "assets/$ASSET_DIR 不存在"
                return false
            }
            var files = 0
            fun copy(assetPath: String, destDir: File) {
                val children = am.list(assetPath) ?: return
                if (children.isEmpty()) {
                    // 是文件
                    destDir.parentFile?.mkdirs()
                    am.open(assetPath).use { ins ->
                        FileOutputStream(destDir).use { out -> ins.copyTo(out, 65536) }
                    }
                    files++
                    return
                }
                destDir.mkdirs()
                for (c in children) copy("$assetPath/$c", File(destDir, c))
            }
            for (t in top) copy("$ASSET_DIR/$t", File(root, t))

            Boot.trace(ctx, "网关资源释放完成，$files 个文件")
            stamp.writeText(System.currentTimeMillis().toString())
            true
        } catch (t: Throwable) {
            lastError = "释放失败: ${t.javaClass.simpleName}: ${t.message}"
            Boot.trace(ctx, lastError)
            false
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

        // 标准名 → 可能的实际文件名（按优先级）
        //
        // 为什么这么乱：Android 打包只接受 lib<name>.so 格式，Termux 的
        // libicuuc.so.79.1 这种多级版本号会被静默丢弃。所以 CI 把它重命名成
        // libicuuc79.so，运行时再建回标准名软链。下面每个条目都要列全所有可能。
        val needs = mapOf(
            "libcrypto.so.3" to listOf("libcrypto.so.3", "libcrypto3.so"),
            "libssl.so.3" to listOf("libssl.so.3", "libssl3.so"),
            "libz.so.1" to listOf("libz.so.1", "libz1.so", "libz13.so"),
            "libsqlite3.so" to listOf("libsqlite3.so", "libsqlite354.so", "libsqlite3.so.3.54.1"),
            "libicuuc.so.79" to listOf("libicuuc.so.79", "libicuuc79.so", "libicuuc.so.78", "libicuuc78.so"),
            "libicui18n.so.79" to listOf("libicui18n.so.79", "libicui18n79.so", "libicui18n.so.78", "libicui18n78.so"),
            "libicudata.so.79" to listOf("libicudata.so.79", "libicudata79.so", "libicudata.so.78", "libicudata78.so"),
            "libcares.so" to listOf("libcares.so"),
            "libc++_shared.so" to listOf("libc++_shared.so"),
        )

        return try {
            libDir.mkdirs()
            for ((standard, candidates) in needs) {
                // 已经能用标准名找到，跳过
                val direct = File(nativeDir, standard)
                if (direct.exists()) continue

                val src = candidates.asSequence()
                    .map { File(nativeDir, it) }
                    .firstOrNull { it.exists() }
                if (src == null) {
                    lastError = "缺原生库: $standard（候选 ${candidates.joinToString()} 都不在）"
                    Boot.trace(ctx, lastError)
                    return false
                }
                val dst = File(libDir, standard)
                if (dst.exists()) dst.delete()
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
                append("  \"session\": { \"dmScope\": \"per-channel-peer\" }\n")
                append("}\n")
            }

            val existing = if (configFile.exists()) configFile.readText() else ""
            // 三个必须项：token 对得上、chatCompletions 开了、端口对
            val ok = existing.contains(TOKEN) &&
                    existing.contains("chatCompletions") &&
                    existing.contains("\"port\": $PORT")
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
                    root.toString(2)
                } else desired
            } catch (_: Throwable) {
                desired
            }
            configFile.writeText(merged)
            Boot.trace(ctx, "网关配置已写入（token + chatCompletions 已开）")
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
            pb.redirectErrorStream(true)
            val logFile = File(root, "gateway.log")
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))

            val p = pb.start()
            proc = p
            lastProc = p
            startSteps.add("spawned")
            Boot.trace(ctx, "网关进程已启动 pid=" + pidOf(p))

            // 等最多 40 秒看端口是否起来
            val ok = waitPort(40_000)
            if (!ok) {
                lastError = "网关启动后 40 秒端口仍不通"
                // 关键：进程可能启动即死（如 node 报错退出），把这个信号告诉界面
                val died = try { !p.isAlive } catch (_: Throwable) { false }
                startSteps.add(if (died) "进程已退出" else "端口超时")
                Boot.trace(ctx, lastError)
            } else {
                startSteps.add("port ok")
            }
            ok
        } catch (t: Throwable) {
            lastError = "启动失败: ${t.javaClass.simpleName}: ${t.message}"
            Boot.trace(ctx, lastError)
            false
        }
    }

    private fun pidOf(p: Process): String = try {
        val f = Process::class.java.getDeclaredField("pid")
        f.isAccessible = true
        f.get(p).toString()
    } catch (_: Throwable) { "?" }

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
