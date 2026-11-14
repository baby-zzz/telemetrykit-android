package app.babyzzz.telemetrykit.androidx

import android.app.Application
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One call that wires everything an Android app needs no code for. Call it once in `Application.onCreate`, after
 * `Telemetry.start`. Idempotent; every part is optional ([AutoInstrumentation]) and none can throw into the app.
 *
 * ```kotlin
 * Telemetry.start(...)
 * TelemetryAndroid.install(this)
 * ```
 */
public object TelemetryAndroid {
    private val installed = AtomicBoolean(false)

    public fun install(application: Application, options: AutoInstrumentation = AutoInstrumentation()) {
        if (!installed.compareAndSet(false, true)) return
        step { if (options.uncaughtExceptions) TelemetryCrashes.install() }
        step { if (options.lifecycle) TelemetryLifecycle.install() }
        step { if (options.taps && ComposeSupport.present) application.registerActivityLifecycleCallbacks(ActivityTapInstaller(options.tapIds)) }
        step { if (options.activityViews) application.registerActivityLifecycleCallbacks(ActivityViewNamer()) }
        step {
            if (options.exitReasons) {
                val store = options.store ?: SharedPreferencesTelemetryStore(application)
                Thread({ ExitReasons.report(application, store) }, "telemetrykit-exit").also { it.isDaemon = true }.start()
            }
        }
    }

    private inline fun step(block: () -> Unit) {
        try {
            block()
        } catch (_: Throwable) {
            // telemetry never throws into the app
        }
    }

    internal fun resetForTest() = installed.set(false)
}
