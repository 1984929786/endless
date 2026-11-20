package com.xundao.yongwuzhijing

import android.content.Context
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.takeWhile
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * llama.cpp 引擎适配层（替换掉原来的 Cactus 引擎）。
 *
 * 为什么换：
 * 2026-11-20 查明原 Cactus 引擎崩溃的根因是**模型权重格式不匹配** ——
 * 我们给的 config.txt 是 HuggingFace 原生格式（architectures/dtype/bfloat16），
 * 而 libcactus.so 读的是它自家的 Cactus Quants 格式（num_kv_shared_layers/layer_N 等）。
 * 官方文档 docs/compatibility.md 明确写：权重必须从 HF 的 Cactus-Compute 组织按版本下载，
 * 版本一变旧权重就加载不了。这是个闭源生态，向上游要饭，不适合长期维护。
 *
 * 换成 llama.cpp 的理由：
 * 1. GGUF 是公开格式，模型到处都有（HF / hf-mirror / ModelScope）
 * 2. 官方维护 Android 绑定（examples/llama.android/lib），不用自己写 JNI
 * 3. 全世界几十万个 App 在用，踩坑的人多，路是通的
 *
 * ⚠️ 接口设计：对外仍然保持「加载 / 推理」两个动作，不暴露 Flow 和协程，
 *    这样 ModelBridge 那 638 行只需要改装载路径，不用重写。
 */
object LlamaEngine {

    private const val TAG = "LlamaEngine"

    /** 推理默认最多生成多少 token */
    private const val DEFAULT_PREDICT_LENGTH = 1024

    @Volatile private var engine: InferenceEngine? = null
    @Volatile private var loadedPath: String? = null
    @Volatile private var loaded = false

    /** 引擎内部用协程，自己持一个 scope */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 初始化（进程内只做一次，绑定 native backend） */
    @Synchronized
    fun ensureInit(context: Context): Boolean {
        if (engine != null) return true
        return try {
            Stage.mark("LlamaEngine: 取 engine 实例")
            val e = AiChat.getInferenceEngine(context)
            Stage.mark("LlamaEngine: engine 实例已拿到 state=${e.state.value}")
            // ai_chat.cpp 在构造时就把 backend 绑好了，state 会直接从 Uninitialized 走到 Initialized。
            // 若卡在 Initializing 或进了 Error，给 30 秒。”
            val ok: Boolean? = runBlocking {
                withTimeoutOrNull(30_000L) {
                    var result: Boolean? = null
                    while (result == null) {
                        when (e.state.value) {
                            is InferenceEngine.State.Initialized,
                            is InferenceEngine.State.ModelReady -> result = true
                            is InferenceEngine.State.Error -> result = false
                            else -> kotlinx.coroutines.delay(50)
                        }
                    }
                    result
                }
            }
            if (ok != true) {
                Stage.mark("LlamaEngine: backend 初始化未成功 state=${e.state.value}")
                return false
            }
            engine = e
            Stage.mark("LlamaEngine: backend 初始化完成 state=${e.state.value}")
            true
        } catch (t: Throwable) {
            Stage.mark("LlamaEngine 初始化失败: ${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }

    /**
     * 加载 GGUF 模型文件。
     *
     * @param modelFile 指向单个 .gguf 文件（不是目录！这是 llama.cpp 和 Cactus 最大的差别）
     */
    fun load(context: Context, modelFile: File): Boolean {
        if (loaded && loadedPath == modelFile.absolutePath) {
            Stage.mark("LlamaEngine: 模型已加载，跳过")
            return true
        }
        if (!ensureInit(context)) return false

        val e = engine ?: return false
        if (!modelFile.isFile) {
            Stage.mark("LlamaEngine: 模型文件不存在 ${modelFile.absolutePath}")
            return false
        }
        Stage.mark("LlamaEngine: 模型文件 ${modelFile.absolutePath} ${modelFile.length() / 1048576}MB")

        return try {
            val latch = CountDownLatch(1)
            // 用 Atomic 引用而非局部 var：值在子线程写、主线程读，局部 var 跨协程不可见
            val result = java.util.concurrent.atomic.AtomicBoolean(false)
            val errRef = java.util.concurrent.atomic.AtomicReference<String?>(null)

            Stage.mark("LlamaEngine: 即将 loadModel()")
            scope.launch {
                try {
                    e.loadModel(modelFile.absolutePath)
                    result.set(true)
                    Stage.mark("LlamaEngine: loadModel 返回成功")
                } catch (t: Throwable) {
                    errRef.set("${t.javaClass.simpleName}: ${t.message}")
                    Stage.mark("LlamaEngine: loadModel 异常 ${errRef.get()}")
                } finally {
                    latch.countDown()
                }
            }

            val done = latch.await(10, TimeUnit.MINUTES)
            if (!done) {
                Stage.mark("LlamaEngine: loadModel 超时（10分钟）")
                return false
            }
            if (!result.get()) {
                Stage.mark("LlamaEngine: 加载失败 ${errRef.get()}")
                return false
            }
            loaded = true
            loadedPath = modelFile.absolutePath
            Stage.mark("LlamaEngine: 模型加载成功")
            true
        } catch (t: Throwable) {
            Stage.mark("LlamaEngine: load 异常 ${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }

    /**
     * 同步推理。把 Flow 收集成一个完整字符串。
     *
     * 为什么用阻塞：
     * ModelBridge 的 /v1/chat/completions 是同步 HTTP 接口，本来就阻塞。
     * 这里用 runBlocking 把 Flow 收完再返回，语义和原来 Cactus 的 complete() 一致。
     */
    fun complete(prompt: String, systemPrompt: String? = null): String? {
        if (!loaded) return null
        val e = engine ?: return null
        return try {
            runBlocking {
                this@LlamaEngine.stageMark("LlamaEngine: 推理开始")
                if (!systemPrompt.isNullOrBlank()) {
                    e.setSystemPrompt(systemPrompt)
                }
                val sb = StringBuilder()
                val job = launch {
                    try {
                        e.sendUserPrompt(prompt, DEFAULT_PREDICT_LENGTH).collect { piece ->
                            sb.append(piece)
                            // 防止模型失控刷爆内存
                            if (sb.length > 512 * 1024) {
                                this@LlamaEngine.stageMark("LlamaEngine: 输出超长，主动停止")
                                throw kotlinx.coroutines.CancellationException("输出过长")
                            }
                        }
                    } catch (ce: kotlinx.coroutines.CancellationException) {
                        // 超长截断或被外部取消，保留已经生成的部分
                        this@LlamaEngine.stageMark("LlamaEngine: 生成被取消（已生成 ${sb.length} 字符）")
                    } catch (t: Throwable) {
                        this@LlamaEngine.stageMark("LlamaEngine: 生成异常 ${t.javaClass.simpleName}: ${t.message}")
                    }
                }
                // 最多等 5 分钟
                val finished = withTimeoutOrNull(5 * 60 * 1000L) { job.join() }
                if (finished == null) {
                    job.cancel()
                    this@LlamaEngine.stageMark("LlamaEngine: 推理超时")
                }
                sb.toString()
            }
        } catch (t: Throwable) {
            Stage.mark("LlamaEngine: complete 异常 ${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }

    /** 卸载模型（释放内存，但保留 backend） */
    fun unload() {
        if (!loaded) return
        try {
            engine?.cleanUp()
            Stage.mark("LlamaEngine: 已 unload")
        } catch (t: Throwable) {
            Stage.mark("LlamaEngine: unload 异常 ${t.message}")
        }
        loaded = false
        loadedPath = null
    }

    /** 彻底销毁（退出应用时） */
    fun destroy() {
        try { engine?.destroy() } catch (_: Throwable) {}
        try { scope.cancel() } catch (_: Throwable) {}
        engine = null
        loaded = false
        loadedPath = null
        Stage.mark("LlamaEngine: 已 destroy")
    }

    private fun stageMark(msg: String) {
        try { Stage.mark(msg) } catch (_: Throwable) {}
    }
}
