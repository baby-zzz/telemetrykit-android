package app.babyzzz.telemetrykit.androidx

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class TelemetryViewsTest {
    private lateinit var telemetry: TestTelemetry

    @Before
    fun setUp() {
        TelemetryViews.resetForTest()
        telemetry = TestTelemetry()
    }

    @After
    fun tearDown() = telemetry.close()

    @Test
    fun `one event per change, none for the same name again`() {
        TelemetryViews.changed("monitor")
        TelemetryViews.changed("monitor")
        TelemetryViews.changed("settings")
        TelemetryViews.changed("monitor")
        val events = telemetry.events().filter { it.name == "view_changed" }
        assertEquals(3, events.size)
        assertNull(events.first().attributes)
    }

    @Test
    fun `the view is in meta of the event`() {
        TelemetryViews.changed("monitor")
        val names = telemetry.viewNames()
        assertEquals("monitor", names.last())
    }

    @Test
    fun `null clears the view and records nothing`() {
        TelemetryViews.changed("monitor")
        TelemetryViews.changed(null)
        assertEquals(1, telemetry.events().count { it.name == "view_changed" })
        TelemetryViews.changed("monitor")
        assertEquals(2, telemetry.events().count { it.name == "view_changed" })
    }

    @Test
    fun `automatic names stop once the app names its screens`() {
        TelemetryViews.changedAutomatically("MainActivity")
        TelemetryViews.changed("monitor")
        TelemetryViews.changedAutomatically("MainActivity")
        assertEquals(2, telemetry.events().count { it.name == "view_changed" })
        assertEquals("monitor", telemetry.viewNames().last())
    }

    @Test
    fun `without a client nothing throws`() {
        telemetry.close()
        TelemetryViews.changed("monitor")
    }
}
