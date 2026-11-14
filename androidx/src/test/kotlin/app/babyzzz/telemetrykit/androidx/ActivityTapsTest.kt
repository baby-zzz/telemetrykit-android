package app.babyzzz.telemetrykit.androidx

import android.app.Application
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.ComposeView
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class ActivityTapsTest {
    private lateinit var telemetry: TestTelemetry

    @Before
    fun setUp() {
        TapReporter.resetForTest()
        TelemetryViews.resetForTest()
        TelemetryAndroid.resetForTest()
        telemetry = TestTelemetry()
    }

    @After
    fun tearDown() = telemetry.close()

    private fun find(node: SemanticsNode, tag: String): SemanticsNode? =
        if (node.config.getOrNull(SemanticsProperties.TestTag) == tag) node else node.children.firstNotNullOfOrNull { find(it, tag) }

    private fun launch(withModifier: Boolean): ComponentActivity {
        val app = ApplicationProvider.getApplicationContext<Application>()
        TelemetryAndroid.install(app, AutoInstrumentation(lifecycle = false, exitReasons = false, uncaughtExceptions = false))
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).create()
        controller.get().setContentView(ComposeView(controller.get()).apply { setContent {
            Box(if (withModifier) Modifier.trackTaps("main") else Modifier) {
                Column {
                    Box(Modifier.size(80.dp).testTag("start-button").clickable(role = Role.Button) {})
                    Box(Modifier.size(80.dp).testTag("camera-12345").clickable(role = Role.Button) {})
                }
            }
        } })
        controller.start().resume().visible()
        shadowOf(Looper.getMainLooper()).idle()
        return controller.get()
    }

    private fun tapOn(activity: ComponentActivity, tag: String) {
        val decor = activity.window.decorView
        val root = TapSemantics.roots(decor).first()
        val bounds = find(root.semanticsOwner.unmergedRootSemanticsNode, tag)!!.boundsInRoot
        val location = IntArray(2)
        (root as View).getLocationInWindow(location)
        val x = location[0] + bounds.center.x
        val y = location[1] + bounds.center.y
        val down = SystemClock.uptimeMillis()
        decor.dispatchTouchEvent(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0))
        decor.dispatchTouchEvent(MotionEvent.obtain(down, down + 20, MotionEvent.ACTION_UP, x, y, 0))
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun clicks() = telemetry.events().filter { it.name == "click" }.map { it.attributes }

    @Test
    fun `a tap on the activity content is recorded without any modifier`() {
        val activity = launch(withModifier = false)
        tapOn(activity, "start-button")
        assertEquals(listOf(mapOf("element_id" to "start-button", "element_tag" to "button", "element_class" to "main")), clicks())
    }

    @Test
    fun `a data like tag is masked in automatic mode`() {
        val activity = launch(withModifier = false)
        tapOn(activity, "camera-12345")
        assertEquals("[masked]", clicks().single()!!["element_id"])
    }

    @Test
    fun `a tap seen by the window and by the modifier is recorded once`() {
        val activity = launch(withModifier = true)
        tapOn(activity, "start-button")
        assertEquals(1, clicks().size)
    }

    @Test
    fun `the resumed activity names the screen once`() {
        launch(withModifier = false)
        val changes = telemetry.events().filter { it.name == "view_changed" }
        assertEquals(1, changes.size)
        assertTrue(telemetry.viewNames().contains("ComponentActivity"))
    }

    @Test
    fun `install twice registers once`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        TelemetryAndroid.install(app, AutoInstrumentation(lifecycle = false, exitReasons = false, uncaughtExceptions = false))
        TelemetryAndroid.install(app)
    }
}
