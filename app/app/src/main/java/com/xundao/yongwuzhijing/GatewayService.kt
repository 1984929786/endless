package com.xundao.yongwuzhijing

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat

/**
 * GatewayService —— 让 node 网关在锁屏 / 后台存活的前台服务。
 *
 * 【为什么必须有这个（2026-11-22 根因修复）】
 * 之前网关是在 Activity 里 ProcessBuilder 直接拉起的普通子进程。Android 的进程优先级里
 * 这种进程属于「cached/background」，一旦锁屏或内存紧张就被冻结、被华为省电策略杀掉。
 * 表现就是：文件释放到一半断了、网关启动一阵就死、每次都要从头来。
 *
 * 对照：能正常跑的 com.xundao.openclaw 同时具备
 *   WAKE_LOCK / FOREGROUND_SERVICE / REQUEST_IGNORE_BATTERY_OPTIMIZATIONS / RECEIVE_BOOT_COMPLETED
 * 我们之前只有 INTERNET。这不是打包问题，是进程生存问题。
 *
 * 本服务做三件事：
 *   1. startForeground 提升进程优先级（用户可见通知）
 *   2. 持有一个 PARTIAL_WAKE_LOCK，锁屏时 CPU 不停
 *   3. 网关进程本身仍由 GatewayLauncher 管理，这里只负责「活着」
 */
class GatewayService : Service() {

    companion object {
        const val CHANNEL_ID = "ywzj_gateway"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.xundao.yongwuzhijing.STOP_GATEWAY"

        fun start(ctx: Context) {
            val i = Intent(ctx, GatewayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i)
            } else {
                ctx.startService(i)
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, GatewayService::class.java))
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val n: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("网关运行中")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }

        // PARTIAL_WAKE_LOCK：锁屏后 CPU 继续跑，屏幕可以关。
        // 这是 node 网关能活过锁屏的关键。不设 timeout，由服务生命周期兜住。
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ywzj:gateway")
            wakeLock?.setReferenceCounted(false)
            wakeLock?.acquire()
            Boot.trace(this, "前台服务已启动，WAKE_LOCK 已持有")
        } catch (t: Throwable) {
            Boot.trace(this, "WAKE_LOCK 申请失败（不影响启动）: ${t.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Throwable) {
        }
        wakeLock = null
        Boot.trace(this, "前台服务已停止，WAKE_LOCK 已释放")
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(CHANNEL_ID, "网关运行", NotificationManager.IMPORTANCE_LOW)
        ch.setShowBadge(false)
        nm.createNotificationChannel(ch)
    }
}
