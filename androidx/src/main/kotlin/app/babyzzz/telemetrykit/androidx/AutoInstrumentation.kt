package app.babyzzz.telemetrykit.androidx

import app.babyzzz.telemetrykit.TelemetryStore

/**
 * What [TelemetryAndroid.install] turns on; each part has its own switch, all on by default.
 *
 * @property lifecycle process lifecycle to `Telemetry.onStart` / `onStop` ([TelemetryLifecycle])
 * @property exitReasons `app_exit` events for how the process died last time, API 30+ ([ExitReasons], on a background thread)
 * @property taps `click` events for taps on the Compose content of every Activity, without `Modifier.trackTaps`
 * @property activityViews screen views named after the resumed Activity ([TelemetryViews]); coarse for a single-Activity app
 * @property uncaughtExceptions crashes recorded and chained to the previous handler ([TelemetryCrashes])
 * @property tapIds what is sent as a tap's `element_id`: [TapIds.Automatic], or a strict list
 * @property store where the exit-reason watermark is kept; null = SharedPreferences `telemetrykit` ([SharedPreferencesTelemetryStore])
 */
public data class AutoInstrumentation(
    val lifecycle: Boolean = true,
    val exitReasons: Boolean = true,
    val taps: Boolean = true,
    val activityViews: Boolean = true,
    val uncaughtExceptions: Boolean = true,
    val tapIds: TapIds = TapIds.Automatic,
    val store: TelemetryStore? = null,
)
