package com.xundao.yongwuzhijing

import android.app.Application
import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 最早期的启动追踪。
 *
 * 为什么要在 Application 里做：
 * MainActivity 里装 CrashCatcher 太晚了 —— 如果崩在 super.onCreate 或
 * System.loadLibrary，根本轮不到装 handler。
 *
 * 为什么写外部存储：
 * 应用私有目录沙盒读不到，但 /sdcard/Android/data/<pkg>/files 我自己也读不到
 * （Android 11+ 限制）。所以优先写 /sdcard/Download —— 这个目录两边都能读。
 */
class YwzjApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // 先装阶段性记录仪，再做任何事 —— 后面任何一行崩了都能看到停在哪
        Stage.attach(this)
        Boot.trace(this, "Application.onCreate")
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            Stage.javaCrash(t, e)
            Boot.crash(this, t, e)
            // 继续走系统默认处理（让系统也记一份）
            val prev = Thread.getDefaultUncaughtExceptionHandler()
            prev?.uncaughtException(t, e)
        }
        Stage.mark("onCreate 完成")
    }
}

/** 极简、无依赖、任何阶段都能写的追踪器 */
object Boot {

    private const val TAG = "YwzjBoot"

    private fun stamp(): String =
        SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())

    /** 优先写外部 Download（沙盒和我都能读），失败退回私有目录 */
    private fun targets(ctx: Context): List<File> {
        val list = ArrayList<File>()
        try {
            val dl = File("/storage/emulated/0/Download")
            if (dl.isDirectory) list.add(File(dl, "ywzj-boot.log"))
        } catch (_: Throwable) {}
        try { list.add(File(ctx.filesDir, "boot.log")) } catch (_: Throwable) {}
        return list
    }

    fun trace(ctx: Context, msg: String) {
        val line = "${stamp()}  $msg\n"
        for (f in targets(ctx)) {
            try {
                f.appendText(line)
            } catch (_: Throwable) {}
        }
        android.util.Log.i(TAG, msg)
    }

    fun crash(ctx: Context, t: Thread, e: Throwable) {
        val sb = StringBuilder()
        sb.append("\n===== CRASH ${stamp()} =====\n")
        sb.append("thread: ${t.name}\n")
        sb.append("type: ${e.javaClass.name}\n")
        sb.append("msg: ${e.message}\n")
        sb.append("device: ${Build.MANUFACTURER} ${Build.MODEL} / SDK ${Build.VERSION.SDK_INT} / ${Build.SUPPORTED_ABIS.joinToString()}\n")
        val sw = java.io.StringWriter()
        e.printStackTrace(java.io.PrintWriter(sw))
        sb.append(sw.toString())
        sb.append("===== END =====\n\n")

        for (f in targets(ctx)) {
            try { f.appendText(sb.toString()) } catch (_: Throwable) {}
        }
        android.util.Log.e(TAG, "CRASH", e)
    }
}
