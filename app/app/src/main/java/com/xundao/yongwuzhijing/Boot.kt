package com.xundao.yongwuzhijing

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 极简日志：写文件 + Logcat。
 * 路径：files/yongwuzhijing/yyMMdd.log（按日滚动）
 */
object Boot {

    private const val TAG = "YWZJ"
    private const val DIR = "yongwuzhijing"
    private val fmt = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    }

    fun trace(ctx: Context, msg: String) {
        val ts = fmt.get()?.format(Date()) ?: "???"
        val line = "[$ts] $msg"
        Log.i(TAG, line)
        try {
            val dir = File(ctx.filesDir, DIR)
            dir.mkdirs()
            val today = SimpleDateFormat("yyMMdd", Locale.US).format(Date())
            File(dir, "$today.log").appendText("$line\n")
        } catch (_: Throwable) { /* 日志写不写不影响 App */ }
    }
}

