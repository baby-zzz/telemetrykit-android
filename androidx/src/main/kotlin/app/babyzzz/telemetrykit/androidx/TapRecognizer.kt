package app.babyzzz.telemetrykit.androidx

import kotlin.math.hypot

/**
 * Recognises a tap in a stream of pointer events: one pointer down, no move beyond the touch slop, up before the
 * long-press timeout. A second pointer, a cancel or a long press ruins the gesture. Pure logic, no Android types.
 */
internal class TapRecognizer(private val slop: Float, private val longPressMillis: Long) {
    private var active = false
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L

    fun down(x: Float, y: Float, time: Long, pointers: Int) {
        if (pointers != 1) {
            active = false
            return
        }
        active = true
        downX = x
        downY = y
        downTime = time
    }

    fun move(x: Float, y: Float, pointers: Int) {
        if (!active) return
        if (pointers != 1 || hypot(x - downX, y - downY) > slop) active = false
    }

    /** The last pointer went up: whether the gesture was a tap */
    fun up(x: Float, y: Float, time: Long): Boolean {
        val tap = active && hypot(x - downX, y - downY) <= slop && time - downTime < longPressMillis
        active = false
        return tap
    }

    fun cancel() {
        active = false
    }
}
