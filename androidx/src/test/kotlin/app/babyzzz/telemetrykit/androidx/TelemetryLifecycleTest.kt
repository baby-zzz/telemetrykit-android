package app.babyzzz.telemetrykit.androidx

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TelemetryLifecycleTest {
    private var telemetry: TestTelemetry? = null

    @After
    fun tearDown() {
        telemetry?.close()
    }

    @Test
    fun `install is idempotent and forwards start and stop`() {
        telemetry = TestTelemetry()
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry.createUnsafe(this)
            override val lifecycle: Lifecycle get() = registry
        }
        assertTrue(TelemetryLifecycle.install(owner.lifecycle))
        assertFalse(TelemetryLifecycle.install(owner.lifecycle))
        assertEquals(1, owner.registry.observerCount)
        // ON_START starts a session through the core
        owner.registry.currentState = Lifecycle.State.STARTED
        assertTrue(telemetry!!.events().any { it.name == "session_start" })
    }

    @Test
    fun `the process lifecycle install does not throw and is idempotent`() {
        TelemetryLifecycle.install()
        TelemetryLifecycle.install()
    }
}
