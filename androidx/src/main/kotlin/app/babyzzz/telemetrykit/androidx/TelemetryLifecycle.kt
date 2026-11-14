package app.babyzzz.telemetrykit.androidx

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import app.babyzzz.telemetrykit.Telemetry
import java.util.Collections
import java.util.WeakHashMap

/** Wires the process lifecycle to the core: `onStart` / `onStop` of [Telemetry] (sessions, writing and sending the queue) */
public object TelemetryLifecycle {
    private val installed: MutableSet<Lifecycle> = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))

    /** Idempotent. Call from the main thread (e.g. `Application.onCreate`); from another thread it is posted to the main one */
    public fun install() {
        try {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                install(ProcessLifecycleOwner.get().lifecycle)
            } else {
                Handler(Looper.getMainLooper()).post { runCatching { install(ProcessLifecycleOwner.get().lifecycle) } }
            }
        } catch (_: Exception) {
            // telemetry never throws into the app
        }
    }

    internal fun install(lifecycle: Lifecycle): Boolean {
        if (!installed.add(lifecycle)) return false
        lifecycle.addObserver(Bridge)
        return true
    }

    internal object Bridge : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) = Telemetry.onStart()

        override fun onStop(owner: LifecycleOwner) = Telemetry.onStop()
    }
}
