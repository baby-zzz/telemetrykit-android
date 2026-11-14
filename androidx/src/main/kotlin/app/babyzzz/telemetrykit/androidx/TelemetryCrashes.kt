package app.babyzzz.telemetrykit.androidx

import android.os.Looper
import app.babyzzz.telemetrykit.FaroException
import app.babyzzz.telemetrykit.FaroTimestamp
import app.babyzzz.telemetrykit.Telemetry
import app.babyzzz.telemetrykit.TelemetryItem
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Captures uncaught exceptions. [install] chains a default `Thread.UncaughtExceptionHandler`: it records the crash
 * and then ALWAYS calls the handler that was installed before it (Crashlytics, the platform's), after its own work
 * whatever happened in it, so crash reporters still get the crash and the process dies the normal way. Install order
 * relative to other crash reporters does not matter for delivery: whoever is installed later calls the earlier one.
 *
 * What is recorded: a Faro exception through the core, like [Telemetry.error]: `type` = the class simple name, `value`
 * = the core's `errorKind` and scrubbed message, no stack frames; context `where` = "uncaught", `thread` = "main" or
 * "background" (never the thread's name) and `fatal` = "true". The queue is then written to the pending file
 * synchronously ([app.babyzzz.telemetrykit.TelemetryClient.persist]); nothing is sent from the dying process, the next
 * start restores the file and sends it. The gates of the core apply: nothing is recorded without consent, without a
 * started client or with the remote flag off.
 *
 * Bounded: the recording runs on a helper thread the handler waits for at most one second, every Throwable inside
 * is swallowed, and a crash while recording (or a second crash meanwhile) is not recorded again.
 *
 * The next start's `app_exit` (reason `crash`, [ExitReasons]) is a second, independent signal of the same crash.
 */
public object TelemetryCrashes {
    private val installed = AtomicBoolean(false)
    private val recording = AtomicBoolean(false)
    private const val WAIT_MILLIS = 1_000L

    /** Idempotent. Call early, e.g. in `Application.onCreate`, after or before `Telemetry.start` (a crash before it is not recorded) */
    public fun install() {
        try {
            if (!installed.compareAndSet(false, true)) return
            Thread.setDefaultUncaughtExceptionHandler(Handler(Thread.getDefaultUncaughtExceptionHandler(), WAIT_MILLIS))
        } catch (_: Throwable) {
            installed.set(false)
        }
    }

    internal class Handler(private val previous: Thread.UncaughtExceptionHandler?, private val waitMillis: Long, private val mainThread: () -> Thread? = { Looper.getMainLooper()?.thread }) :
        Thread.UncaughtExceptionHandler {
        override fun uncaughtException(thread: Thread, error: Throwable) {
            try {
                capture(thread, error)
            } catch (_: Throwable) {
                // never let ours get in the way of the crash
            } finally {
                previous?.uncaughtException(thread, error)
            }
        }

        private fun capture(thread: Thread, error: Throwable) {
            if (!recording.compareAndSet(false, true)) return
            try {
                val isMain = runCatching { thread === mainThread() }.getOrDefault(false)
                val worker = Thread({ runCatching { record(isMain, error) } }, "telemetrykit-crash")
                worker.isDaemon = true
                worker.start()
                worker.join(waitMillis)
            } finally {
                recording.set(false)
            }
        }
    }

    internal fun record(isMain: Boolean, error: Throwable) {
        val client = Telemetry.client ?: return
        val type = error::class.java.simpleName.ifEmpty { "Throwable" }
        val context = mapOf("where" to "uncaught", "thread" to if (isMain) "main" else "background", "fatal" to "true")
        client.record(TelemetryItem.Exception(FaroException(type, client.configuration.errorValue(error), FaroTimestamp.string(client.nowMillis), context)))
        client.persist()
    }

    internal fun resetForTest() {
        installed.set(false)
        recording.set(false)
    }
}
