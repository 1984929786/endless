package com.xundao.yongwuzhijing

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var native: NativeBridge
    private lateinit var gw: GatewayLauncher

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        Boot.trace(applicationContext, "MainActivity.onCreate 入口")
        CrashCatcher.install(applicationContext)
        CrashCatcher.stage("onCreate 开始")

        Boot.trace(applicationContext, "super.onCreate 之前")
        super.onCreate(savedInstanceState)
        Boot.trace(applicationContext, "super.onCreate 之后")

        try {
            Boot.trace(applicationContext, "创建 NativeBridge")
            native = NativeBridge(applicationContext)
            Boot.trace(applicationContext, "创建 GatewayLauncher")
            gw = GatewayLauncher.get(applicationContext)

            // 网关在后台线程启动
            Thread({
                try {
                    val t0 = System.currentTimeMillis()
                    val ok = gw.start()
                    val ms = System.currentTimeMillis() - t0
                    Boot.trace(applicationContext, "内嵌网关启动结果=$ok 耗时=${ms}ms")
                    if (ok) {
                        val cfg = java.io.File(applicationContext.filesDir, "openclaw/home/openclaw.json")
                        val gotToken = AgentClient.loadToken(cfg)
                        Boot.trace(applicationContext, "网关 token 读取结果=$gotToken | ${gw.diag()}")
                    } else {
                        Boot.trace(applicationContext, "网关启动失败: ${gw.lastError()} | ${gw.diag()}")
                    }
                } catch (t: Throwable) {
                    Boot.trace(applicationContext, "网关启动异常: ${t.javaClass.simpleName}: ${t.message}")
                }
            }, "gateway-boot").start()

            Boot.trace(applicationContext, "创建 WebView")

            window.decorView.post { gw.requestIgnoreBatteryOptimizations(this) }
            web = WebView(this)
            setContentView(web)

            web.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                allowContentAccess = true
                loadWithOverviewMode = true
                useWideViewPort = true
                textZoom = 100
                mediaPlaybackRequiresUserGesture = false
            }

            web.setBackgroundColor(0xFF0d0f13.toInt())
            web.isVerticalScrollBarEnabled = false
            web.overScrollMode = WebView.OVER_SCROLL_NEVER

            web.addJavascriptInterface(native, "Native")

            web.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    Log.i(TAG, "页面加载完成 $url")
                    CrashCatcher.stage("页面加载完成")
                }
            }

            web.webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                    Log.i(TAG, "[JS] ${m.message()} @${m.lineNumber()}")
                    return true
                }
            }

            web.loadUrl("file:///android_asset/ui/index.html")
            Boot.trace(applicationContext, "已加载 HTML")
            CrashCatcher.stage("onCreate 完成")

        } catch (t: Throwable) {
            Log.e(TAG, "onCreate 失败", t)
            CrashCatcher.stage("onCreate 失败: ${t.message}")
            finish()
        }
    }

    override fun onDestroy() {
        CrashCatcher.stage("onDestroy")
        try {
            web.loadUrl("about:blank")
            web.removeJavascriptInterface("Native")
            web.destroy()
        } catch (t: Throwable) {
            Log.w(TAG, "清理异常", t)
        }
        super.onDestroy()
    }

    override fun onPause() {
        super.onPause()
        try { web.onPause() } catch (_: Throwable) {}
    }

    override fun onResume() {
        super.onResume()
        try { web.onResume() } catch (_: Throwable) {}
    }

    override fun onBackPressed() {
        web.evaluateJavascript(
            "(function(){try{return window.__onBack&&window.__onBack()}catch(e){return false}})()"
        ) { r ->
            if (r == null || r == "null" || r == "false") {
                @Suppress("DEPRECATION")
                super.onBackPressed()
            }
        }
    }

    companion object {
        private const val TAG = "YWZJ"
    }
}
