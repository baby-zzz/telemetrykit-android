package app.babyzzz.telemetrykit.androidx

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.os.Build
import app.babyzzz.telemetrykit.Telemetry
import app.babyzzz.telemetrykit.TelemetryStore

/** One process exit as the platform recorded it: only the three numbers we use; never the description or the trace */
internal data class ExitRecord(val timestamp: Long, val reason: Int, val importance: Int)

/** Where the exits come from (the ActivityManager; a fake in tests) */
internal fun interface ProcessExitSource {
    fun recent(): List<ExitRecord>
}

/**
 * Why the app's process died last time (API 30+, `ActivityManager.getHistoricalProcessExitReasons`): one `app_exit`
 * event per exit, with `reason` and `importance`. Reading is non-destructive (Crashlytics' own ANR reporting is
 * unaffected), and neither the ANR trace stream nor the description text is ever read. Android 7-10 have no such API
 * (Play vitals only).
 */
public object ExitReasons {
    public const val DEFAULT_KEY: String = "telemetry_exit_watermark"
    private const val MAX_RECORDS = 16

    /**
     * Reports the exits newer than the timestamp watermark kept in [store] under [key], oldest first, and moves the
     * watermark. The first run (no watermark) only stores the newest timestamp and reports nothing, so a new
     * installation of this library never floods. Does nothing before API 30 or before `Telemetry.start`.
     *
     * Blocking (an IPC to the system): call it off the main thread, e.g. from a background executor, once at start.
     * [TelemetryAndroid.install] does that. Returns how many events were recorded; never throws.
     */
    public fun report(context: Context, store: TelemetryStore, key: String = DEFAULT_KEY): Int = report(context, store, key, Build.VERSION.SDK_INT)

    /** [report] with the platform level given, so tests cover the pre-API 30 path on one Robolectric runtime */
    @SuppressLint("NewApi") // the level check is the first line, with sdkInt = Build.VERSION.SDK_INT outside tests
    internal fun report(context: Context, store: TelemetryStore, key: String, sdkInt: Int): Int {
        if (sdkInt < Build.VERSION_CODES.R) return 0
        return try {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return 0
            val packageName = context.packageName
            report(
                source = {
                    manager.getHistoricalProcessExitReasons(packageName, 0, MAX_RECORDS).map { ExitRecord(it.timestamp, it.reason, it.importance) }
                },
                store = store,
                key = key,
            )
        } catch (_: Throwable) {
            0
        }
    }

    internal fun report(source: ProcessExitSource, store: TelemetryStore, key: String): Int {
        // Without a started client the events would be dropped but the watermark would move: wait for the next start
        if (Telemetry.client == null) return 0
        val records = try {
            source.recent()
        } catch (_: Exception) {
            return 0
        }
        val newest = records.maxOfOrNull { it.timestamp }
        val watermark = store.getString(key)?.toLongOrNull()
        if (watermark == null) {
            store.putString(key, (newest ?: 0L).toString())
            return 0
        }
        val fresh = records.filter { it.timestamp > watermark }.sortedBy { it.timestamp }
        for (record in fresh) {
            Telemetry.event("app_exit", mapOf("reason" to reasonName(record.reason), "importance" to importanceName(record.importance)))
        }
        if (newest != null && newest > watermark) store.putString(key, newest.toString())
        return fresh.size
    }

    // ApplicationExitInfo.REASON_* (API 30; FREEZER 31; PACKAGE_STATE_CHANGE and PACKAGE_UPDATED 33), as numbers so older SDKs compile and lint stays quiet
    internal fun reasonName(reason: Int): String = when (reason) {
        0 -> "unknown"
        1 -> "exit_self"
        2 -> "signaled"
        3 -> "low_memory"
        4 -> "crash"
        5 -> "crash_native"
        6 -> "anr"
        7 -> "initialization_failure"
        8 -> "permission_change"
        9 -> "excessive_resource_usage"
        10 -> "user_requested"
        11 -> "user_stopped"
        12 -> "dependency_died"
        13 -> "other"
        14 -> "freezer"
        15 -> "package_state_change"
        16 -> "package_updated"
        else -> "unknown"
    }

    // RunningAppProcessInfo.IMPORTANCE_*: FOREGROUND 100, FOREGROUND_SERVICE 125, VISIBLE 200, PERCEPTIBLE 230, SERVICE 300,
    // TOP_SLEEPING 325, CANT_SAVE_STATE 350, CACHED (= BACKGROUND) 400 and up
    internal fun importanceName(importance: Int): String = when {
        importance <= 100 -> "foreground"
        importance <= 125 -> "foreground"
        importance <= 230 -> "visible"
        importance <= 300 -> "service"
        importance <= 350 -> "background"
        importance < 1000 -> "cached"
        else -> "other"
    }
}
