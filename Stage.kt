package com.xundao.yongwuzhijing

import android.content.Context
import android.os.Build
import java.io.File
import java.io.RandomAccessFile

/**
 * 崩溃现场记录仪（native 崩溃也算）。
 *
 * 为什么需要它：
 * 2026-11-20 道友反馈「点释放几秒后闪退、无崩溃记录」。
 * Java 的 UncaughtExceptionHandler 抓不到 native SIGSEGV；系统 tombstone 要 root；
 * dumpsys dropbox 要 DUMP 权限。三条路全死。
 *
 * 所以只能自己写一条：**每次进入一个阶段，先把阶段名 fsync 到磁盘，再干活**。
 * 崩溃后文件里最后一行就是死掉的位置。这是唯一不依赖系统权限的取证方式。
 *
 * 关键设计：
 * 1. 用 FileDescriptor.sync()，不用 appendText —— appendText 走缓冲，进程瞬死会丢内容
 * 2. 每行带时间戳和 elapsed，能看出是「立刻死」还是「跑了几秒才死」
 * 3. 同时写 Download（我能读）和私有目录（应用能读），互为备份
 * 4. 记录 RSS/可用内存，用来判断是不是被 LMK 杀掉
 */
object Stage {

    private const val DOWNLOAD = "/storage/emulated/0/Download/ywzj-stage.log"

    private var ctx: Context? = null
    private var startAt = 0L

    /** 在 Application.onCreate 里调一次 */
    fun attach(context: Context) {
        ctx = context.applicationContext
        startAt = System.currentTimeMillis()
        // 每次启动先分割上一次的记录，避免无限增长
        for (p in paths(context)) {
            try {
                val f = File(p)
                if (f.isFile && f.length() > 256 * 1024) {
                    val old = File(p + ".old")
                    f.renameTo(old)
                }
            } catch (_: Throwable) {}
        }
        mark("========== 启动 pid=${android.os.Process.myPid()} ${Build.MANUFACTURER} ${Build.MODEL} ==========")
    }

    private fun paths(context: Context): List<String> {
        val l = ArrayList<String>()
        l.add(DOWNLOAD)
        try { l.add(File(context.filesDir, "stage.log").absolutePath) } catch (_: Throwable) {}
        return l
    }

    /**
     * 打一个阶段标记。**同步落盘**，进程下一秒被 SIGKILL 也已经写进去了。
     */
    fun mark(tag: String) {
        val c = ctx ?: return
        val elapsed = if (startAt > 0) System.currentTimeMillis() - startAt else 0L
        val mem = memLine()
        val line = "[${elapsed}ms] $tag | $mem\n"
        for (p in paths(c)) {
            try {
                RandomAccessFile(p, "rw").use { raf ->
                    raf.seek(raf.length())
                    raf.write(line.toByteArray(Charsets.UTF_8))
                    // 关键：强制刷到闪存，不做这一步等于没写
                    raf.fd.sync()
                }
            } catch (_: Throwable) {
                // Download 写不了就退到私有目录，两个都不行就算了（不能让记录本身崩掉主流程）
            }
        }
        android.util.Log.i("YwzjStage", tag)
    }

    /** 当前进程的内存状态，用来判断是否接近被 LMK 回收 */
    private fun memLine(): String {
        var rss = -1L
        var avail = -1L
        try {
            // 自己进程的 RSS（单位 KB）
            File("/proc/self/status").forEachLine { l ->
                if (l.startsWith("VmRSS:")) {
                    rss = l.substringAfter("VmRSS:").trim().split(Regex("\\s+"))[0].toLongOrNull() ?: -1L
                }
            }
        } catch (_: Throwable) {}
        try {
            File("/proc/meminfo").forEachLine { l ->
                if (l.startsWith("MemAvailable:")) {
                    avail = l.substringAfter("MemAvailable:").trim().split(Regex("\\s+"))[0].toLongOrNull() ?: -1L
                }
            }
        } catch (_: Throwable) {}
        return "rss=${rss / 1024}MB avail=${avail / 1024}MB"
    }

    /** Java 层未捕获异常也记一笔（和 native 崩溃区分开） */
    fun javaCrash(t: Thread, e: Throwable) {
        val sb = StringBuilder()
        sb.append("[${System.currentTimeMillis() - startAt}ms] !!! JAVA CRASH thread=${t.name} ${e.javaClass.name}: ${e.message}\n")
        val sw = java.io.StringWriter()
        e.printStackTrace(java.io.PrintWriter(sw))
        sb.append(sw.toString())
        val c = ctx ?: return
        for (p in paths(c)) {
            try {
                RandomAccessFile(p, "rw").use { raf ->
                    raf.seek(raf.length())
                    raf.write(sb.toString().toByteArray(Charsets.UTF_8))
                    raf.fd.sync()
                }
            } catch (_: Throwable) {}
        }
    }
}
