package com.xundao.yongwuzhijing

import com.cactus.CactusEngine

/**
 * 让上层不用直接摸 native 句柄。
 *
 * ⚠️ 关键：Cactus native 层会**保留**传进去的 char*/指针而不是拷贝
 * （官方 issue #66/#130 证实：needle_load 保留 buffer 指针，wrapper 释放后就 SIGSEGV）。
 *
 * 所以所有传进 native 的 Java 对象（消息 JSON、选项 JSON、回调）都必须在推理全程
 * 被强引用住，否则 GC 一回收就是野指针 → 无堆栈的 SIGSEGV。
 * 这不是可选优化，是正确性要求。
 */
object CactusEngineHolder {

    @Volatile var currentHandle: Long = 0L

    // 推理期间强引用，防止 GC 回收被 native 借用的对象
    @Volatile private var holdMessages: String? = null
    @Volatile private var holdOptions: String? = null

    fun complete(messagesJson: String, optionsJson: String?): String? {
        val h = currentHandle
        if (h == 0L) return null

        holdMessages = messagesJson
        holdOptions = optionsJson
        try {
            return CactusEngine.complete(h, messagesJson, optionsJson, null)
        } finally {
            holdMessages = null
            holdOptions = null
        }
    }

    fun stop() {
        val h = currentHandle
        if (h != 0L) {
            try { CactusEngine.stop(h) } catch (_: Throwable) {}
        }
    }

    fun lastError(): String = CactusEngine.lastError()
}
