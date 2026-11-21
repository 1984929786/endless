package com.xundao.yongwuzhijing

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var bridge: ModelBridge
    private lateinit var native: NativeBridge
    private lateinit var gw: GatewayLauncher

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        Boot.trace(applicationContext, "MainActivity.onCreate 入口")
        // 必须在 super.onCreate 之前装，才能抓到 onCreate 里的异常
        CrashCatcher.install(applicationContext)
        CrashCatcher.stage("onCreate 开始")

        Boot.trace(applicationContext, "super.onCreate 之前")
        super.onCreate(savedInstanceState)
        Boot.trace(applicationContext, "super.onCreate 之后")

        try {
            Boot.trace(applicationContext, "创建 ModelBridge")
            bridge = ModelBridge(applicationContext)
            Boot.trace(applicationContext, "创建 NativeBridge")
            native = NativeBridge(applicationContext, bridge)
            Boot.trace(applicationContext, "创建 GatewayLauncher")
            gw = GatewayLauncher(applicationContext)

            // 网关在后台线程启动：首次要释放 400MB assets，不能堵主线程
            Thread({
                try {
                    val t0 = System.currentTimeMillis()
                    val ok = gw.start()
                    val ms = System.currentTimeMillis() - t0
                    Boot.trace(applicationContext, "内嵌网关启动结果=$ok 耗时=${ms}ms")
                    if (ok) {
                        // 网关首次启动会自己生成 token 写进配置，必须读回来
                        val cfg = java.io.File(applicationContext.filesDir, "openclaw/home/openclaw.json")
                        val gotToken = AgentClient.loadToken(cfg)
                        Boot.trace(applicationContext, "读取网关 token: $gotToken")
                    } else {
                        Boot.trace(applicationContext, "网关启动失败: ${gw.lastError()}")
                    }
                } catch (t: Throwable) {
                    Boot.trace(applicationContext, "网关启动异常: ${t.javaClass.simpleName}: ${t.message}")
                }
            }, "gateway-boot").start()

            Boot.trace(applicationContext, "创建 WebView")
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
        // 顺序很重要：先停 WebView，再销毁模型，避免 JS 回调打到已释放的句柄
        try {
            web.loadUrl("about:blank")
            web.removeJavascriptInterface("Native")
            web.destroy()
        } catch (t: Throwable) {
            Log.w(TAG, "webview 清理异常", t)
        }
        try { bridge.destroyModel() } catch (t: Throwable) { Log.w(TAG, "模型清理异常", t) }
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
        // 交给 JS 处理返回（关抽屉/子页面），JS 不处理才退出
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
