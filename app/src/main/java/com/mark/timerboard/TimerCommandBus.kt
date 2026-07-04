package com.mark.timerboard

object TimerCommandBus {
    private var pauseAllHandler: (() -> Unit)? = null
    private var resetAllHandler: (() -> Unit)? = null

    fun register(
        onPauseAll: () -> Unit,
        onResetAll: () -> Unit
    ) {
        pauseAllHandler = onPauseAll
        resetAllHandler = onResetAll
    }

    fun clear() {
        pauseAllHandler = null
        resetAllHandler = null
    }

    fun pauseAll(): Boolean {
        val handler = pauseAllHandler ?: return false
        handler()
        return true
    }

    fun resetAll(): Boolean {
        val handler = resetAllHandler ?: return false
        handler()
        return true
    }
}
