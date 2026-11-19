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
    private lateinit var bridge: ModelBridge
    private lateinit var native: NativeBridge

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        bridge = ModelBridge(applicationContext)
        native = NativeBridge(applicationContext, bridge)

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
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                Log.i(TAG, "[JS] ${m.message()} @${m.lineNumber()}")
                return true
            }
        }

        web.loadUrl("file:///android_asset/ui/index.html")

        Log.i(TAG, "MainActivity 启动")
    }

    override fun onDestroy() {
        // 顺序很重要：先停 WebView，再销毁模型，避免 JS 回调打到已释放的句柄
        try {
            web.loadUrl("about:blank")
            web.removeJavascriptInterface("Native")
            web.destroy()
        } catch (t: Throwable) {
            Log.w(TAG, "webview 清理异常", t)
        }
        bridge.stop()
        bridge.destroyModel()
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
