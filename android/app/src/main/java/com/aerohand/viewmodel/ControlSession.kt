package com.aerohand.viewmodel

/** One epoch per control connection. Discard any delayed producer from an older epoch. */
internal class ControlSession {
    private var epoch = 0L
    var gestureEnabled: Boolean = false
        private set

    fun invalidate() { disableGesture() }

    fun disableGesture() {
        epoch++
        gestureEnabled = false
    }

    fun enableGesture() { gestureEnabled = true }

    fun snapshot(): Long = epoch

    fun isCurrent(snapshot: Long): Boolean = snapshot == epoch

    fun acceptsGesture(snapshot: Long): Boolean = isCurrent(snapshot) && gestureEnabled
}
