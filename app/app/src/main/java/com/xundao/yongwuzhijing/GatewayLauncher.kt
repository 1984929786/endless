package com.xundao.yongwuzhijing

import android.content.Context
import java.io.File

/**
 * GatewayLauncher — 在 App 进程内拉起内嵌的 OpenClaw 网关。
 *
 * 架构：
 *   永无止境 App = 手 + 面板，全部在一个包里。
 *   网关跑在本 App 自己的私有目录，用自己的端口和 token。
 *   AI「脑」由 StepFun API 提供（通过 OpenClaw provider 配置）。
 *
 * 运行时来源：
 *   CI 把 openclaw npm 包（含 node 运行时 + 所有依赖）打包进
 *   assets/openclaw/runtime/，首次启动释放到 files/openclaw/runtime/。
 *
 * 原生库依赖：
 *   libnode.so libcares.so libc++_shared.so 等 Node.js 运行时所需 .so
 *   已在 files/lib 建软链（CI 处理）。
 */
class GatewayLauncher(private val ctx: Context) {

    companion object {
        private const val TAG = "YwzjGw"

        @Volatile private var lastProc: Process? = null

        fun procAlive(): Boolean = try {
            INSTANCE?.proc?.isAlive == true
        } catch (t: Throwable) { false }

        fun runningProcAlive(): Boolean = procAlive()

        /** 内嵌网关端口。不能是 18789（寻道在用）。 */
        const val PORT = 18800

        /** 内嵌网关的固定 token（只绑 loopback，不对外暴露）。 */
        const val TOKEN = "ywzj-stepfun"

        /** StepFun API Key 存在 SharedPreferences 的键名 */
        private const val PREF_KEY = "stepfun_api_key"

        /** 默认模型：StepFun 旗舰 */
        const val DEFAULT_MODEL = "step-3.7-flash"

        fun currentModel(): String = DEFAULT_MODEL

        /** 读/写 StepFun API key */
        fun apiKey(): String = try {
            appCtx?.getSharedPreferences("ywzj_prefs", Context.MODE_PRIVATE)
                ?.getString(PREF_KEY, "").orEmpty()
        } catch (_: Throwable) { "" }

        fun setApiKey(key: String) {
            appCtx?.getSharedPreferences("ywzj_prefs", Context.MODE_PRIVATE)
                ?.edit()?.putString(PREF_KEY, key.trim())?.apply()
        }

        @Volatile private var appCtx: Context? = null

        private const val ASSET_DIR = "openclaw"
        private const val STAMP = ".extracted-v3"

        @Volatile private var INSTANCE: GatewayLauncher? = null

        fun get(ctx: Context): GatewayLauncher =
            INSTANCE ?: synchronized(this) {
                appCtx = ctx.applicationContext
                INSTANCE ?: GatewayLauncher(ctx.applicationContext).also { INSTANCE = it }
            }
    }

    // ---- 路径 ----

    private val root: File get() = File(ctx.filesDir, ASSET_DIR)
    private val home: File get() = File(root, "home")
    private val state: File get() = File(root, "state")
    private val libDir: File get() = File(ctx.filesDir, "lib")
    private val configFile: File get() = File(home, "openclaw.json")
    private val workspaceDir: File get() = File(home, "workspace")
    private val runtimeDir: File get() = File(root, "runtime")

    @Volatile private var proc: Process? = null
    @Volatile private var lastError: String = ""

    fun lastError(): String = lastError

    fun diag(): String {
        val steps = startSteps.joinToString(" > ")
        val p = proc
        val myPid = if (p != null && p.isAlive) pidOf(p) else "-"
        val occupied = if (portBusy()) "是" else "否"
        val extracted = if (runtimeDir.exists()) runtimeDir.walkTopDown().count { it.isFile } else 0
        return "steps=[$steps] pid=$myPid 端口占用=$occupied runtime文件=$extracted" +
                (if (lastError.isBlank()) "" else " err=$lastError")
    }

    @Volatile private var startSteps: MutableList<String> =
        java.util.Collections.synchronizedList(ArrayList())

    // ---- 生命周期 ----

    fun isRunning(): Boolean {
        val p = proc
        if (p != null && p.isAlive && probePort()) return true
        return probePort()
    }

    private fun portBusy(): Boolean = try {
        val s = java.net.Socket()
        s.connect(java.net.InetSocketAddress("127.0.0.1", PORT), 1200)
        s.close()
        true
    } catch (_: Throwable) { false }

    private fun killByPort() {
        val killed = mutableListOf<String>()
        try {
            try { proc?.let { killed.add(pidOf(it) + "(proc)"); it.destroyForcibly() } } catch (_: Throwable) {}
            proc = null

            val myUid = android.os.Process.myUid()
            val myPid = android.os.Process.myPid()
            java.io.File("/proc").list()?.forEach { d ->
                val pid = d.toIntOrNull() ?: return@forEach
                if (pid == myPid) return@forEach
                val uid = try {
                    java.io.File("/proc/$d/status").useLines { l ->
                        l.firstOrNull { it.startsWith("Uid:") }
                            ?.removePrefix("Uid:")?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.toIntOrNull()
                    }
                } catch (_: Throwable) { null }
                if (uid != myUid) return@forEach
                val cmd = try {
                    java.io.File("/proc/$d/cmdline").readText().replace('\u0000', ' ')
                } catch (_: Throwable) { "" }
                if (!cmd.contains("openclaw") && !cmd.contains("libnode")) return@forEach
                android.os.Process.sendSignal(pid, 9)
                killed.add("$pid(cmdline)")
            }
            Boot.trace(ctx, "killByPort: 已杀 $killed")
        } catch (t: Throwable) {
            Boot.trace(ctx, "killByPort 失败: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    // ---- 配置 ----

    fun rewriteConfig(): Boolean = writeDefaultConfig()

    private fun writeDefaultConfig(): Boolean {
        return try {
            home.mkdirs()
            val key = apiKey()
            val model = DEFAULT_MODEL
            val cfg = buildString {
                appendLine("{")
                appendLine("  \"gateway\": {")
                appendLine("    \"auth\": {")
                appendLine("      \"token\": \"$TOKEN\"")
                appendLine("    },")
                appendLine("    \"server\": {")
                appendLine("      \"port\": $PORT")
                appendLine("    }")
                appendLine("  },")
                appendLine("  \"models\": {")
                appendLine("    \"mode\": \"merge\",")
                appendLine("    \"providers\": {")
                appendLine("      \"stepfun\": {")
                appendLine("        \"baseUrl\": \"https://api.stepfun.com/v1\",")
                appendLine("        \"api\": \"openai-completions\",")
                if (key.isNotBlank()) {
                    appendLine("        \"apiKey\": \"${org.json.JSONObject.quote(key)}\",")
                }
                appendLine("        \"models\": [{")
                appendLine("          \"id\": \"$model\",")
                appendLine("          \"name\": \"StepFun $model\",")
                appendLine("          \"contextWindow\": 128000,")
                appendLine("          \"maxTokens\": 8192,")
                appendLine("          \"api\": \"openai-completions\"")
                appendLine("        }]")
                appendLine("      }")
                appendLine("    }")
                appendLine("  },")
                appendLine("  \"tools\": {")
                appendLine("    \"allow\": [\"fs_read\", \"fs_write\", \"fs_delete\", \"fs_list\", \"exec\", \"browser\", \"web_search\", \"memory\"],")
                appendLine("    \"deny\": []")
                appendLine("  },")
                appendLine("  \"workspace\": {")
                appendLine("    \"templates\": {\"AGENTS.md\": \"\", \"SOUL.md\": \"\", \"TOOLS.md\": \"\", \"IDENTITY.md\": \"\", \"USER.md\": \"\", \"HEARTBEAT.md\": \"\"}")
                appendLine("  }")
                appendLine("}")
            }
            configFile.writeText(cfg)
            Boot.trace(ctx, "配置已写入: ${configFile.absolutePath}")
            true
        } catch (t: Throwable) {
            lastError = "写配置失败: ${t.message}"
            Boot.trace(ctx, lastError)
            false
        }
    }

    // ---- 启动 ----

    fun start(): Boolean {
        val t0 = System.currentTimeMillis()
        startSteps.clear()
        lastError = ""

        try {
            // Step 1: 释放运行时
            startSteps.add("准备释放")
            if (!extractRuntime()) {
                lastError = "运行时释放失败"
                return false
            }
            startSteps.add("运行时就绪")

            // Step 2: 写配置
            startSteps.add("写配置")
            if (!writeDefaultConfig()) {
                lastError = "配置写入失败"
                return false
            }

            // Step 3: 建软链
            startSteps.add("建软链")
            ensureSymlinks()

            // Step 4: 确保工作区目录
            startSteps.add("建工作区")
            workspaceDir.mkdirs()

            // Step 5: 杀旧进程
            startSteps.add("清理旧进程")
            killByPort()

            // Step 6: 拉进程
            startSteps.add("拉进程")
            if (!spawnGateway()) {
                lastError = "网关进程启动失败"
                return false
            }

            // Step 7: 等端口
            startSteps.add("等端口")
            val ok = waitPort(30000)
            if (!ok) {
                lastError = "端口 $PORT 30 秒内未响应"
                Boot.trace(ctx, lastError)
                // 进程可能还在跑，不算完全失败
            }

            val ms = System.currentTimeMillis() - t0
            startSteps.add("完成(${ms}ms)")
            Boot.trace(ctx, "网关启动完成 耗时${ms}ms")
            return true
        } catch (t: Throwable) {
            lastError = "启动异常: ${t.javaClass.simpleName}: ${t.message}"
            Boot.trace(ctx, lastError)
            return false
        }
    }

    // ---- 运行时释放 ----

    private fun extractRuntime(): Boolean {
        val stamp = File(root, STAMP)
        if (stamp.exists() && runtimeDir.exists()) {
            val count = runtimeDir.walkTopDown().count { it.isFile }
            if (count > 100) {
                Boot.trace(ctx, "运行时已释放 ($count 文件)，跳过")
                return true
            }
        }

        return try {
            root.mkdirs()
            // 从 assets/openclaw/runtime/ 释放到 files/openclaw/runtime/
            // CI 已经把 openclaw 运行时打包进 assets 了
            val assetsRuntime = "openclaw/runtime"
            val assets = ctx.assets
            val entries = assets.list(assetsRuntime) ?: emptyArray()

            Boot.trace(ctx, "释放运行时: ${entries.size} 个顶层条目")
            entries.forEach { entry ->
                copyAssetDir(assets, "$assetsRuntime/$entry", runtimeDir)
            }

            stamp.writeText("v3")
            Boot.trace(ctx, "运行时释放完成")
            true
        } catch (t: Throwable) {
            lastError = "释放运行时失败: ${t.message}"
            Boot.trace(ctx, lastError)
            false
        }
    }

    private fun copyAssetDir(assets: android.content.res.AssetManager, assetPath: String, destDir: File) {
        val children = assets.list(assetPath) ?: return
        val targetDir = File(destDir, assetPath.substringAfter("openclaw/runtime/"))
        targetDir.mkdirs()

        children.forEach { child ->
            val childPath = "$assetPath/$child"
            try {
                val subChildren = assets.list(childPath)
                if (subChildren != null && subChildren.isNotEmpty()) {
                    copyAssetDir(assets, childPath, destDir)
                } else {
                    val outFile = File(destDir, childPath.substringAfter("openclaw/runtime/"))
                    outFile.parentFile?.mkdirs()
                    assets.open(childPath).use { input ->
                        java.io.FileOutputStream(outFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                }
            } catch (e: Throwable) {
                Boot.trace(ctx, "复制 $childPath 失败: ${e.message}")
            }
        }
    }

    // ---- 软链 ----

    private fun ensureSymlinks() {
        // Node.js 运行时需要这些 .so 软链
        // CI 已经把正确的 .so 放进了 assets/openclaw/runtime/node_modules/ 等位置
        // 这里只确保 libDir 下的基础软链
        try {
            val nativeDir = ctx.applicationInfo.nativeLibraryDir
            val links = mapOf(
                "libnode.so" to "$nativeDir/libnode.so",
                "libc++_shared.so" to "$nativeDir/libc++_shared.so",
                "libcares.so" to "$nativeDir/libcares.so"
            )
            links.forEach { (name, target) ->
                val link = File(libDir, name)
                if (!link.exists() && File(target).exists()) {
                    try {
                        java.nio.file.Files.createSymbolicLink(link.toPath(), File(target).toPath())
                    } catch (_: Throwable) {
                        // 软链失败就硬拷
                        File(target).copyTo(link, overwrite = true)
                    }
                }
            }
        } catch (_: Throwable) {}
    }

    // ---- 进程管理 ----

    private fun spawnGateway(): Boolean = try {
        val nodeBin = File(runtimeDir, "node_modules/openclaw/openclaw.mjs")
        if (!nodeBin.exists()) {
            // 降级：找顶层 node
            val topNode = File(runtimeDir, "node_modules/openclaw/dist/index.js")
            if (!topNode.exists()) {
                lastError = "找不到 openclaw 入口: ${nodeBin.absolutePath}"
                Boot.trace(ctx, lastError)
                return false
            }
        }

        val nodeExe = File(runtimeDir, "node_modules/openclaw/node")
        val nodeBinFile = File(runtimeDir, "node")
        val nodePath = when {
            nodeExe.exists() -> nodeExe.absolutePath
            nodeBinFile.exists() -> nodeBinFile.absolutePath
            else -> {
                // 用系统 node（如果有）
                val which = execWhich("node")
                if (which != null) which else {
                    lastError = "找不到 node 二进制"
                    return false
                }
            }
        }

        val nativeDir = ctx.applicationInfo.nativeLibraryDir
        val pb = ProcessBuilder(
            nodePath,
            File(runtimeDir, "node_modules/openclaw/openclaw.mjs").absolutePath,
            "gateway"
        )
        pb.directory(runtimeDir)
        pb.redirectErrorStream(true)
        val env = pb.environment()
        env["LD_LIBRARY_PATH"] = "${libDir.absolutePath}:$nativeDir"
        env["HOME"] = home.absolutePath
        env["TMPDIR"] = ctx.cacheDir.absolutePath
        env["OPENCLAW_HOME"] = home.absolutePath
        env["OPENCLAW_STATE_DIR"] = state.absolutePath
        env["OPENCLAW_CONFIG_PATH"] = configFile.absolutePath
        env["OPENCLAW_GATEWAY_PORT"] = PORT.toString()
        env["OPENCLAW_ALLOW_MULTI_GATEWAY"] = "true"
        env["PATH"] = "/system/bin:/system/xbin"

        val p = pb.start()
        proc = p

        // 后台读日志
        Thread {
            try {
                p.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        Boot.trace(ctx, "[gw] $line")
                    }
                }
            } catch (_: Throwable) {}
        }.start()

        Boot.trace(ctx, "网关进程已启动 pid=${pidOf(p)}")
        true
    } catch (t: Throwable) {
        lastError = "spawn 失败: ${t.javaClass.simpleName}: ${t.message}"
        Boot.trace(ctx, lastError)
        false
    }

    private fun execWhich(name: String): String? = try {
        val p = Runtime.getRuntime().exec("which $name")
        p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
        p.inputStream.bufferedReader().readLine()
    } catch (_: Throwable) { null }

    // ---- 端口探测 ----

    private fun probePort(): Boolean = try {
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
    } catch (_: Throwable) { false }

    private fun waitPort(ms: Int): Boolean {
        val deadline = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < deadline) {
            if (probePort()) return true
            Thread.sleep(800)
        }
        return false
    }

    // ---- 日志导出 ----

    fun exportLog(): String = try {
        val src = File(ctx.filesDir, "openclaw/gateway.log")
        if (!src.exists()) return "(无网关日志)"
        val dst = java.io.File(ctx.getExternalFilesDir(null), "gateway-${System.currentTimeMillis()}.log")
        src.copyTo(dst, overwrite = true)
        dst.absolutePath
    } catch (t: Throwable) { "导出失败: ${t.message}" }

    // ---- 工具 ----------------

    fun requestIgnoreBatteryOptimizations(activity: android.app.Activity) {
        try {
            val pm = activity.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
            if (!pm.isIgnoringBatteryOptimizations(activity.packageName)) {
                val intent = android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                intent.data = android.net.Uri.parse("package:" + activity.packageName)
                activity.startActivity(intent)
            }
        } catch (t: Throwable) {
            Boot.trace(ctx, "申请电池优化白名单失败: ${t.message}")
        }
    }

    private fun pidOf(p: Process): String = try {
        Process::class.java.getDeclaredField("pid").apply { isAccessible = true }.get(p).toString()
    } catch (_: Throwable) { "?" }
}

