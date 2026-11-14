package app.babyzzz.telemetrykit.androidx

import app.babyzzz.telemetrykit.Telemetry

/** What a tap landed on: the nearest test tag (null: none) and the `element_tag` for its role */
internal data class TapTarget(val tag: String?, val elementTag: String)

/** Records the `click` event; one tap is reported once even when the automatic tracker and `Modifier.trackTaps` both see it */
internal object TapReporter {
    private val lock = Any()
    private var lastUpTime = Long.MIN_VALUE

    /** [upTime] is the up event's time: the same tap seen twice has the same one */
    fun report(upTime: Long, target: TapTarget, window: String, ids: TapIds) {
        try {
            synchronized(lock) {
                if (upTime == lastUpTime) return
                lastUpTime = upTime
            }
            Telemetry.event(
                "click",
                mapOf("element_id" to ids.resolve(target.tag), "element_tag" to target.elementTag, "element_class" to window),
            )
        } catch (_: Exception) {
            // telemetry never throws into the app
        }
    }

    internal fun resetForTest() {
        synchronized(lock) { lastUpTime = Long.MIN_VALUE }
    }
}
