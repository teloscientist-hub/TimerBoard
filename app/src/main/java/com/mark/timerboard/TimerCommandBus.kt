package com.mark.timerboard

object TimerCommandBus {
    private var pauseAllHandler: (() -> Unit)? = null
    private var resetAllHandler: (() -> Unit)? = null
    private var resumeAllHandler: (() -> Unit)? = null

    fun register(
        onPauseAll: () -> Unit,
        onResetAll: () -> Unit,
        onResumeAll: () -> Unit
    ) {
        pauseAllHandler = onPauseAll
        resetAllHandler = onResetAll
        resumeAllHandler = onResumeAll
    }

    fun clear() {
        pauseAllHandler = null
        resetAllHandler = null
        resumeAllHandler = null
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

    fun resumeAll(): Boolean {
        val handler = resumeAllHandler ?: return false
        handler()
        return true
    }
}
