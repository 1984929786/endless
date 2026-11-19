package com.xundao.yongwuzhijing

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 把未捕获异常写到 filesDir/crash.log，方便事后取。
 * 应用自己 catch 不到的 native crash 也会在下次启动留下痕迹（通过 UDE 标记）。
 */
object CrashCatcher {

    private const val TAG = "YWZJ"
    private var dir: File? = null

    fun install(ctx: Context) {
        dir = ctx.filesDir
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try { write(t, e) } catch (_: Throwable) {}
            prev?.uncaughtException(t, e)
        }
    }

    fun logFile(ctx: Context): File = File(ctx.filesDir, "crash.log")

    /** 记录"启动到某个阶段"，用于定位 native 静默退出 */
    @Volatile private var lastStage: String = ""

    fun stage(s: String) {
        lastStage = s
        val d = dir ?: return
        try {
            File(d, "last_stage.txt").writeText(
                SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date()) + " | " + s
            )
        } catch (_: Throwable) {}
    }

    private fun write(t: Thread, e: Throwable) {
        val d = dir ?: return
        val sw = StringWriter()
        PrintWriter(sw).use { pw ->
            pw.println("===== " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()) + " =====")
            pw.println("thread=" + t.name)
            pw.println("stage=" + lastStage)
            pw.println("device=" + Build.MANUFACTURER + " " + Build.MODEL + " sdk=" + Build.VERSION.SDK_INT)
            pw.println("abi=" + Build.SUPPORTED_ABIS.joinToString(","))
            pw.println("mem(max/avail)=" + Runtime.getRuntime().maxMemory() / 1048576 + "MB")
            e.printStackTrace(pw)
        }
        File(d, "crash.log").appendText(sw.toString() + "\n")
        Log.e(TAG, "崩溃已记录", e)
    }

    fun read(ctx: Context): String {
        val f = logFile(ctx)
        return try {
            if (f.isFile) f.readText().takeLast(6000) else ""
        } catch (_: Throwable) { "" }
    }

    /** 上次启动到了哪一步（判断是不是 native 层直接挂掉） */
    fun lastStageText(ctx: Context): String {
        return try {
            val f = File(ctx.filesDir, "last_stage.txt")
            if (f.isFile) f.readText() else ""
        } catch (_: Throwable) { "" }
    }

    fun clear(ctx: Context) {
        try { logFile(ctx).delete() } catch (_: Throwable) {}
    }
}
