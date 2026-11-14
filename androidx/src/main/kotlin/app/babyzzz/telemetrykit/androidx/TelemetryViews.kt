package app.babyzzz.telemetrykit.androidx

import app.babyzzz.telemetrykit.Telemetry

/**
 * Screen views. [changed] names the screen in view (every later item carries it as Faro `meta.view.name`) and records
 * one `view_changed` event when the name differs from the last one, so a screen that is redrawn or re-resumed counts
 * once. The event has no attributes: the view is in `meta`.
 *
 * Names must be constants from your own enumeration ("monitor", "settings"), never data (a camera name, an id, an
 * e-mail): they are not scrubbed. Once the app has called [changed], the automatic Activity names of
 * [TelemetryAndroid] (`AutoInstrumentation.activityViews`) stop, so the two never fight.
 */
public object TelemetryViews {
    private val lock = Any()
    private var last: String? = null
    private var explicit = false

    /** The screen now in view; null = none. Safe to call from any thread; never throws */
    public fun changed(name: String?) {
        synchronized(lock) { explicit = true }
        apply(name)
    }

    internal fun changedAutomatically(name: String) {
        if (synchronized(lock) { explicit }) return
        apply(name)
    }

    private fun apply(name: String?) {
        try {
            val changed = synchronized(lock) {
                val differs = name != last
                last = name
                differs
            }
            Telemetry.setView(name)
            if (changed && name != null) Telemetry.event(VIEW_CHANGED)
        } catch (_: Exception) {
            // telemetry never throws into the app
        }
    }

    internal fun resetForTest() {
        synchronized(lock) {
            last = null
            explicit = false
        }
    }

    internal const val VIEW_CHANGED = "view_changed"
}
