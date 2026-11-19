package com.xundao.yongwuzhijing

import com.cactus.CactusEngine

/** 让上层不用直接摸 native 句柄 */
object CactusEngineHolder {

    @Volatile var currentHandle: Long = 0L

    fun complete(messagesJson: String, optionsJson: String?): String? {
        val h = currentHandle
        if (h == 0L) return null
        return CactusEngine.complete(h, messagesJson, optionsJson, null)
    }

    fun stop() {
        val h = currentHandle
        if (h != 0L) {
            try { CactusEngine.stop(h) } catch (_: Throwable) {}
        }
    }

    fun lastError(): String = CactusEngine.lastError()
}
