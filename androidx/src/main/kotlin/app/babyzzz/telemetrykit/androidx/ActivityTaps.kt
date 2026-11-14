package app.babyzzz.telemetrykit.androidx

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.Window
import java.util.Collections
import java.util.WeakHashMap

/**
 * Observes the touches of an Activity's window (a `Window.Callback` wrapper) and, for a tap, hits the semantics tree of
 * the Compose roots inside it. It sees the event before the UI does, so the tree is still as the user saw it, and it
 * passes every call on unchanged. Dialogs, sheets and popups are other windows: they need `Modifier.trackTaps`.
 */
internal class TapWindowCallback(
    private val delegate: Window.Callback,
    private val window: Window,
    private val ids: TapIds,
    slop: Float,
    longPressMillis: Long,
) : Window.Callback by delegate {
    private val recognizer = TapRecognizer(slop, longPressMillis)

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        try {
            observe(event)
        } catch (_: Throwable) {
            recognizer.cancel()
        }
        return delegate.dispatchTouchEvent(event)
    }

    private fun observe(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> recognizer.down(event.x, event.y, event.eventTime, event.pointerCount)
            MotionEvent.ACTION_POINTER_DOWN -> recognizer.cancel()
            MotionEvent.ACTION_MOVE -> recognizer.move(event.x, event.y, event.pointerCount)
            MotionEvent.ACTION_UP -> if (recognizer.up(event.x, event.y, event.eventTime)) tapped(event)
            MotionEvent.ACTION_CANCEL -> recognizer.cancel()
        }
    }

    private fun tapped(event: MotionEvent) {
        // Compose classes are touched only here, behind ComposeSupport.present (the installer is only registered then)
        TapSemantics.tapAt(window.decorView, event.x, event.y, event.eventTime, "main", ids)
    }
}

/** Wraps the window callback of every Activity once */
internal class ActivityTapInstaller(private val ids: TapIds) : Application.ActivityLifecycleCallbacks {
    private val wrapped: MutableSet<Window> = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = wrap(activity)

    // An Activity created before the installer ran
    override fun onActivityStarted(activity: Activity) = wrap(activity)

    private fun wrap(activity: Activity) {
        try {
            val window = activity.window ?: return
            val current = window.callback ?: return
            if (!wrapped.add(window)) return
            val configuration = ViewConfiguration.get(activity)
            window.callback = TapWindowCallback(current, window, ids, configuration.scaledTouchSlop.toFloat(), ViewConfiguration.getLongPressTimeout().toLong())
        } catch (_: Throwable) {
            // telemetry never throws into the app
        }
    }

    override fun onActivityResumed(activity: Activity) = Unit

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivityStopped(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) = Unit
}

/** Names the screen after the resumed Activity, until the app names its screens itself (`TelemetryViews.changed`) */
internal class ActivityViewNamer : Application.ActivityLifecycleCallbacks {
    override fun onActivityResumed(activity: Activity) {
        runCatching { TelemetryViews.changedAutomatically(activity::class.java.simpleName.ifEmpty { "Activity" }) }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

    override fun onActivityStarted(activity: Activity) = Unit

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivityStopped(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) = Unit
}
