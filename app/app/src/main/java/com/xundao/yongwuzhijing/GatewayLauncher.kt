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

        /** 内嵌网关端口。**不能是 18789（寻道在用）也不能是 18790（本地模型）。** */
        const val PORT = 18800

        /** assets 里运行时目录名 */
        private const val ASSET_DIR = "openclaw"

        /** 标记文件：释放完成后写一个，下次跳过 */
        private const val STAMP = ".extracted-v1"
    }

    private val root: File get() = File(ctx.filesDir, ASSET_DIR)
    private val home: File get() = File(root, "home")
    private val state: File get() = File(root, "state")
    private val libDir: File get() = File(ctx.filesDir, "lib")
    private val configFile: File get() = File(home, "openclaw.json")

    @Volatile private var proc: Process? = null
    @Volatile private var lastError: String = ""

    fun lastError(): String = lastError

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
        if (isRunning()) return true
        if (!extractAssets()) return false
        if (!ensureSymlinks()) return false
        if (!writeDefaultConfig()) return false
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
     * 建原生库软链。
     *
     * libnode.so 内部记录的依赖名是 libz.so.1 之类，而 APK 里叫 libz13.so
     * （Android 要求 so 名唯一，所以打包时加了版本后缀）。
     * 建同名软链让链接器找得到。
     */
    private fun ensureSymlinks(): Boolean {
        val nativeDir = File(ctx.applicationInfo.nativeLibraryDir)
        val pairs = listOf(
            "libcrypto.so.3" to "libcrypto3.so",
            "libssl.so.3" to "libssl3.so",
            "libz.so.1" to "libz13.so",
            "libsqlite3.so" to "libsqlite354.so",
            "libicui18n.so.78" to "libicui18n78.so",
            "libicuuc.so.78" to "libicuuc78.so",
            "libicudata.so.78" to "libicudata78.so",
            "libcares.so" to "libcares.so",
            "libc++_shared.so" to "libc++_shared.so",
        )
        return try {
            libDir.mkdirs()
            for ((link, target) in pairs) {
                val src = File(nativeDir, target)
                if (!src.exists()) {
                    lastError = "缺原生库: $target"
                    Boot.trace(ctx, lastError)
                    return false
                }
                val dst = File(libDir, link)
                if (dst.exists()) dst.delete()
                // Android 上 java.nio.file.Files.createSymbolicLink 可用（API 26+）
                try {
                    java.nio.file.Files.createSymbolicLink(dst.toPath(), src.toPath())
                } catch (e: Throwable) {
                    // 软链失败就硬拷（占空间但能跑）
                    src.copyTo(dst, overwrite = true)
                }
            }
            true
        } catch (t: Throwable) {
            lastError = "建软链失败: ${t.message}"
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
            if (configFile.exists()) return true
            configFile.writeText(
                """
                {
                  "gateway": {
                    "bind": "loopback",
                    "port": $PORT,
                    "mode": "local"
                  }
                }
                """.trimIndent()
            )
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
            pb.redirectErrorStream(true)
            pb.redirectOutput(File(root, "gateway.log"))

            val p = pb.start()
            proc = p
            Boot.trace(ctx, "网关进程已启动 pid=${pidOf(p)}")

            // 等最多 40 秒看端口是否起来
            val ok = waitPort(40_000)
            if (!ok) {
                lastError = "网关启动后 40 秒端口仍不通，见 ${File(root, "gateway.log").absolutePath}"
                Boot.trace(ctx, lastError)
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
