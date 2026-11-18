package com.xundao.yongwuzhijing

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
import com.cactus.CactusEngine
import org.json.JSONArray
import org.json.JSONObject

/**
 * 首版验证界面：加载模型 + 起本地服务 + 直接对话。
 * 目的只有一个 —— 在真机上确认 Cactus 能跑起来。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var bridge: ModelBridge
    private lateinit var logView: TextView
    private lateinit var replyView: TextView
    private val ui = Handler(Looper.getMainLooper())
    private val history = JSONArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        bridge = ModelBridge(applicationContext)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 48, 24, 24)
        }

        root.addView(TextView(this).apply {
            text = "永无止境 · 本地引擎验证"
            textSize = 20f
            gravity = Gravity.CENTER
        })

        val btnLoad = Button(this).apply { text = "① 加载模型" }
        val btnServer = Button(this).apply { text = "② 起服务 (127.0.0.1:${ModelBridge.port})" }
        val btnChat = Button(this).apply { text = "③ 说句你好" }

        val status = TextView(this).apply {
            text = "未加载"
            textSize = 13f
            setPadding(0, 16, 0, 16)
        }

        replyView = TextView(this).apply {
            textSize = 15f
            setPadding(0, 16, 0, 16)
        }

        logView = TextView(this).apply {
            textSize = 11f
            text = ""
        }

        root.addView(btnLoad)
        root.addView(btnServer)
        root.addView(btnChat)
        root.addView(status)
        root.addView(replyView)
        root.addView(ScrollView(this).apply { addView(logView) })

        setContentView(root)

        btnLoad.setOnClickListener {
            status.text = "加载中…（首次可能要 1~3 分钟，内存紧张时更久）"
            btnLoad.isEnabled = false
            log("开始加载模型")
            bridge.loadModelAsync { ok, msg ->
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
                val raw = CactusEngine.complete(bridge.handle, history.toString(), opts.toString(), null)
                val cost = System.currentTimeMillis() - t0
                ui.post {
                    btnChat.isEnabled = true
                    if (raw == null) {
                        replyView.text = "✗ 推理失败: ${CactusEngine.lastError()}"
                    } else {
                        val text = try {
                            val o = JSONObject(raw)
                            o.optString("response", raw)
                        } catch (t: Throwable) { raw }
                        replyView.text = text
                        log("${cost}ms | 原始返回前 300 字:\n${raw.take(300)}")
                        history.put(JSONObject().put("role", "assistant").put("content", text))
                    }
                }
            }.start()
        }

        log("App 启动，模型目录候选见日志")
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
