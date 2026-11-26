package com.xundao.yongwuzhijing

import android.content.Context
import android.util.Log
import android.webkit.JavascriptInterface
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 给 WebView 的 JS 桥。
 * 负责：网关控制、Agent 开关、API key 管理、诊断、会话存储。
 * 本地模型推理已移除——现在由 StepFun API（通过 OpenClaw 网关）提供 AI 能力。
 */
class NativeBridge(private val context: Context) {

    companion object {
        private const val TAG = "NativeBridge"
    }

    @Volatile private var lastSource: String = "none"
    private val sessionsDir = File(context.filesDir, "sessions").apply { mkdirs() }

    // ---------------- 启动 ----------------

    @JavascriptInterface
    fun bootstrap(): String {
        try { GatewayLauncher.get(context).start() } catch (_: Throwable) {}
        return "ready"
    }

    @JavascriptInterface
    fun status(): String {
        if (AgentClient.enabled && AgentClient.probe()) return "agent"
        if (AgentClient.enabled && AgentClient.lastProbeError.isNotBlank()) {
            CrashCatcher.stage("启动探活失败: " + AgentClient.lastProbeError)
        }
        return "agent"
    }

    @JavascriptInterface
    fun mode(): String {
        if (!AgentClient.enabled) return "off"
        return if (AgentClient.probe()) "agent" else "local"
    }

    @JavascriptInterface
    fun setAgentEnabled(on: Boolean): String {
        AgentClient.enabled = on
        CrashCatcher.stage("Agent 开关: $on")
        return if (on) "on" else "off"
    }

    // ---------------- 网关诊断 ----------------

    @JavascriptInterface
    fun gwInfo(): String {
        val o = JSONObject()
        val gw = GatewayLauncher.get(context)
        o.put("port", GatewayLauncher.PORT)
        o.put("proc", GatewayLauncher.procAlive())
        o.put("probe", AgentClient.probe())
        o.put("health", AgentClient.healthLine())
        o.put("tokenHint", if (AgentClient.token.isEmpty()) "(空)" else AgentClient.token.take(8) + "…" + AgentClient.token.length + "位")
        o.put("home", File(context.filesDir, "openclaw/home").absolutePath)
        o.put("lastNote", AgentClient.lastChatNote)
        o.put("err", AgentClient.lastProbeError)
        o.put("steps", gw.diag())
        val cfg = File(context.filesDir, "openclaw/home/openclaw.json")
        o.put("hasConfig", cfg.exists())
        o.put("extracted", File(context.filesDir, "openclaw/runtime/openclaw.mjs").exists())
        o.put("logTail", tail(File(context.filesDir, "openclaw/gateway.log"), 40))
        return o.toString()
    }

    @JavascriptInterface
    fun gwStart(): String {
        val t0 = System.currentTimeMillis()
        return try {
            val gw = GatewayLauncher.get(context)
            val ok = gw.start()
            val ms = System.currentTimeMillis() - t0
            if (ok) {
                AgentClient.loadToken(File(context.filesDir, "openclaw/home/openclaw.json"))
                "已启动 ${ms}ms | " + gw.diag()
            } else {
                "启动失败 ${ms}ms | " + gw.diag()
            }
        } catch (t: Throwable) {
            "异常: ${t.javaClass.simpleName}: ${t.message}"
        }
    }

    @JavascriptInterface
    fun gwTest(): String {
        val t0 = System.currentTimeMillis()
        val r = AgentClient.chat(listOf(Triple("user", "只回答两个字：在线", "")), "ywzj-diag")
        val ms = System.currentTimeMillis() - t0
        if (r.ok) return "成功 ${ms}ms: " + r.text.take(60)

        val hint = try {
            val log = File(context.filesDir, "openclaw/gateway.log")
            val lines = tail(log, 120).split('\n')
            val key = lines.filter { l ->
                val s = l.lowercase()
                (s.contains("error") || s.contains("warn") || s.contains("fail") ||
                 s.contains("model") || s.contains("provider") || s.contains("agent")) &&
                !s.contains("model-pricing")
            }.takeLast(3).joinToString(" \u23CE ").trim()
            if (key.isNotEmpty()) " | 网关日志: " + key.take(300) else ""
        } catch (e: Throwable) { "" }

        return "失败 ${ms}ms: " + r.error + " | " + AgentClient.lastChatNote + hint
    }

    @JavascriptInterface
    fun agentEnabled(): Boolean = AgentClient.enabled

    @JavascriptInterface
    fun getApiKeyMasked(): String {
        val k = GatewayLauncher.apiKey()
        return if (k.isBlank()) "" else k.take(6) + "…" + k.takeLast(4)
    }

    @JavascriptInterface
    fun setApiKey(key: String): String {
        val k = key.trim()
        if (k.length < 10) return "key 太短"
        GatewayLauncher.setApiKey(k)
        GatewayLauncher.get(context).rewriteConfig()
        return "已保存，重启网关后生效"
    }

    @JavascriptInterface
    fun diag(): String = "src=" + lastSource + " " + diagDetail()

    @JavascriptInterface
    fun netTest(): String = AgentClient.selfTest()

    @JavascriptInterface
    fun crashLog(): String = CrashCatcher.read(context)

    @JavascriptInterface
    fun exportLog(): String = GatewayLauncher.get(context).exportLog()

    @JavascriptInterface
    fun exportCrash(): String = try {
        val txt = "stage: " + CrashCatcher.lastStageText(context) + "\n\n" + CrashCatcher.read(context)
        if (txt.isBlank()) return "没有崩溃记录"
        val f = File(context.getExternalFilesDir(null), "crash-${System.currentTimeMillis()}.txt")
        f.writeText(txt)
        f.absolutePath
    } catch (t: Throwable) { "导出失败: ${t.message}" }

    @JavascriptInterface
    fun lastStage(): String = CrashCatcher.lastStageText(context)

    @JavascriptInterface
    fun memInfo(): String = try {
        val act = Runtime.getRuntime()
        val sb = StringBuilder()
        sb.append("heap").append(" ").append((act.totalMemory() - act.freeMemory()) / 1048576).append("MB/")
            .append(act.maxMemory() / 1048576).append("MB")
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val mi = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        sb.append(" | 系统可用 ").append(mi.availMem / 1048576).append("MB")
        sb.append(" | 低内存=").append(mi.lowMemory)
        sb.toString()
    } catch (t: Throwable) { "未知" }

    @JavascriptInterface
    fun modelInfo(): String = try {
        JSONObject()
            .put("name", "StepFun API")
            .put("ready", AgentClient.probe())
            .toString()
    } catch (t: Throwable) { "{}" }

    // ---------------- 会话存储 ----------------

    @JavascriptInterface
    fun saveSession(id: String, json: String): String = try {
        val f = File(sessionsDir, safeName(id) + ".json")
        f.writeText(json)
        "ok"
    } catch (t: Throwable) { "fail" }

    @JavascriptInterface
    fun listSessions(): String = try {
        val files = sessionsDir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: emptyArray()
        val sorted = files.sortedByDescending { it.lastModified() }.take(50)
        val arr = JSONArray()
        sorted.forEach { f ->
            try { arr.put(JSONObject(f.readText())) } catch (_: Throwable) {}
        }
        arr.toString()
    } catch (t: Throwable) { "[]" }

    @JavascriptInterface
    fun clearSessions(): String = try {
        sessionsDir.listFiles()?.forEach { it.delete() }
        "ok"
    } catch (t: Throwable) { "fail" }

    // ---------------- 工具 ----------------

    @Volatile private var lastChatNote: String = ""

    private fun diagDetail(): String {
        val sb = StringBuilder()
        sb.append("enabled=").append(AgentClient.enabled)
        sb.append(" probe=").append(if (AgentClient.lastProbeOk()) "OK" else "FAIL")
        if (AgentClient.lastProbeError.isNotBlank()) sb.append(" err=").append(AgentClient.lastProbeError)
        sb.append(" last=").append(AgentClient.lastChatNote.ifBlank { "(未调用过)" })
        if (!AgentClient.lastProbeOk()) sb.append(" net=").append(AgentClient.selfTest())
        return sb.toString()
    }

    private fun tail(f: File, n: Int): String = try {
        if (!f.exists()) return "(无日志)"
        f.readLines().takeLast(n).joinToString("\n")
    } catch (e: Throwable) { "(读日志失败: ${e.message})" }

    private fun safeName(s: String): String =
        s.replace(Regex("[^A-Za-z0-9_-]"), "_").take(60).ifEmpty { "s" }
}
