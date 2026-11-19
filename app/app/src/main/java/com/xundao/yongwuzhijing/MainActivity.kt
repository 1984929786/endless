package com.xundao.yongwuzhijing

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var bridge: ModelBridge
    private lateinit var logView: TextView
    private lateinit var replyView: TextView
    private lateinit var status: TextView
    private val ui = Handler(Looper.getMainLooper())
    private val history = JSONArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        askStoragePermission()

        bridge = ModelBridge(applicationContext)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(30, 60, 30, 30)
        }

        root.addView(TextView(this).apply {
            text = "永无止境"
            textSize = 26f
            gravity = Gravity.CENTER
        })

        root.addView(TextView(this).apply {
            text = "本地引擎验证"
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, 6, 0, 24)
        })

        val btnLoad = Button(this).apply { text = "① 加载模型" }
        val btnScan = Button(this).apply { text = "扫 扫描下载目录" }
        val btnServer = Button(this).apply { text = "② 起服务 127.0.0.1:${ModelBridge.port}" }
        val btnChat = Button(this).apply { text = "③ 说句你好" }

        status = TextView(this).apply {
            text = "未加载"
            textSize = 13f
            setPadding(0, 18, 0, 18)
        }

        replyView = TextView(this).apply {
            textSize = 16f
            setPadding(0, 12, 0, 18)
        }

        logView = TextView(this).apply {
            textSize = 11f
            text = ""
        }

        root.addView(btnLoad)
        root.addView(btnScan)
        root.addView(btnServer)
        root.addView(btnChat)
        root.addView(status)
        root.addView(replyView)
        root.addView(ScrollView(this).apply { addView(logView) })

        setContentView(root)

        btnScan.setOnClickListener {
            logView.text = ""
            Thread {
                bridge.scanDownloadDirs { line -> log(line) }
                ui.post { log("扫描结束") }
            }.start()
        }

        btnLoad.setOnClickListener {
            status.text = "加载中…"
            btnLoad.isEnabled = false
            log("开始加载")
            bridge.loadModelAsync(
                onProgress = { m -> ui.post { status.text = m; log(m) } }
            ) { ok, msg ->
                ui.post {
                    btnLoad.isEnabled = true
                    status.text = if (ok) "✓ $msg" else "✗ $msg"
                    log(msg)
                }
            }
        }

        btnServer.setOnClickListener {
            bridge.startServer()
            status.text = "服务已启动 127.0.0.1:${ModelBridge.port}"
            log("HTTP 服务启动")
        }

        btnChat.setOnClickListener {
            if (!bridge.modelReady) {
                status.text = "模型还没加载好"
                return@setOnClickListener
            }
            replyView.text = "思考中…"
            btnChat.isEnabled = false
            Thread {
                val t0 = System.currentTimeMillis()
                history.put(JSONObject().put("role", "user").put("content", "你好，请用一句话介绍你自己。"))
                val opts = JSONObject().put("max_tokens", 256).put("temperature", 0.7)
                    .put("auto_handoff", false)
                val raw = CactusEngineHolder.complete(history.toString(), opts.toString())
                val cost = System.currentTimeMillis() - t0
                ui.post {
                    btnChat.isEnabled = true
                    if (raw == null) {
                        replyView.text = "✗ 推理失败: ${CactusEngineHolder.lastError()}"
                    } else {
                        val text = try {
                            val o = JSONObject(raw)
                            o.optString("response", raw)
                        } catch (t: Throwable) { raw }
                        replyView.text = text
                        log("${cost}ms | 返回前 300 字:\n${raw.take(300)}")
                        history.put(JSONObject().put("role", "assistant").put("content", text))
                    }
                }
            }.start()
        }

        log("App 启动")
    }

    /** Android 10 读 /sdcard/Download 需要运行时权限 */
    private fun askStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
                val p = Manifest.permission.READ_EXTERNAL_STORAGE
                if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(this, arrayOf(p), 1001)
                }
            }
            return
        }
        val p = Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(p), 1001)
        }
    }

    private fun log(s: String) {
        Log.i("YWZJ", s)
        ui.post { logView.text = "${logView.text}\n$s" }
    }

    override fun onDestroy() {
        bridge.stop()
        bridge.destroyModel()
        super.onDestroy()
    }
}

/** 让 MainActivity 不用直接摸句柄 */
object CactusEngineHolder {
    fun complete(messagesJson: String, optionsJson: String?): String? =
        com.cactus.CactusEngine.complete(currentHandle, messagesJson, optionsJson, null)

    fun lastError(): String = com.cactus.CactusEngine.lastError()

    @Volatile var currentHandle: Long = 0L
}
