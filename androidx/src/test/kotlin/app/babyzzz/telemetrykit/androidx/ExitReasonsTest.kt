package app.babyzzz.telemetrykit.androidx

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.babyzzz.telemetrykit.InMemoryTelemetryStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class ExitReasonsTest {
    private lateinit var telemetry: TestTelemetry
    private val key = "wm"

    @Before
    fun setUp() {
        telemetry = TestTelemetry()
    }

    @After
    fun tearDown() = telemetry.close()

    private fun source(vararg records: ExitRecord) = ProcessExitSource { records.toList() }

    @Test
    fun `the first run stores the newest timestamp and reports nothing`() {
        val store = InMemoryTelemetryStore()
        assertEquals(0, ExitReasons.report(source(ExitRecord(100, 4, 100), ExitRecord(300, 6, 400)), store, key))
        assertEquals("300", store.getString(key))
        assertEquals(0, telemetry.events().count { it.name == "app_exit" })
    }

    @Test
    fun `the first run without history stores zero, so the next exit is reported`() {
        val store = InMemoryTelemetryStore()
        ExitReasons.report(source(), store, key)
        assertEquals("0", store.getString(key))
        assertEquals(1, ExitReasons.report(source(ExitRecord(5, 10, 400)), store, key))
    }

    @Test
    fun `newer exits are reported oldest first and the watermark moves`() {
        val store = InMemoryTelemetryStore(mapOf(key to "100"))
        val count = ExitReasons.report(source(ExitRecord(300, 6, 400), ExitRecord(100, 4, 100), ExitRecord(200, 3, 300)), store, key)
        assertEquals(2, count)
        assertEquals("300", store.getString(key))
        val events = telemetry.events().filter { it.name == "app_exit" }
        assertEquals(listOf("app_exit", "app_exit"), events.map { it.name })
        assertEquals(mapOf("reason" to "low_memory", "importance" to "service"), events[0].attributes)
        assertEquals(mapOf("reason" to "anr", "importance" to "cached"), events[1].attributes)
        assertEquals(0, ExitReasons.report(source(ExitRecord(300, 6, 400)), store, key))
    }

    @Test
    fun `no client means no watermark change`() {
        telemetry.close()
        val store = InMemoryTelemetryStore(mapOf(key to "100"))
        assertEquals(0, ExitReasons.report(source(ExitRecord(300, 6, 400)), store, key))
        assertEquals("100", store.getString(key))
        val empty = InMemoryTelemetryStore()
        ExitReasons.report(source(ExitRecord(300, 6, 400)), empty, key)
        assertNull(empty.getString(key))
    }

    @Test
    fun `a failing source reports nothing`() {
        val store = InMemoryTelemetryStore(mapOf(key to "100"))
        assertEquals(0, ExitReasons.report({ error("boom") }, store, key))
        assertEquals("100", store.getString(key))
    }

    @Test
    fun `every reason and importance has a name`() {
        val reasons = listOf(
            "unknown", "exit_self", "signaled", "low_memory", "crash", "crash_native", "anr", "initialization_failure", "permission_change",
            "excessive_resource_usage", "user_requested", "user_stopped", "dependency_died", "other", "freezer", "package_state_change", "package_updated",
        )
        reasons.forEachIndexed { i, name -> assertEquals(name, ExitReasons.reasonName(i)) }
        assertEquals("unknown", ExitReasons.reasonName(99))
        val importances = mapOf(100 to "foreground", 125 to "foreground", 200 to "visible", 230 to "visible", 300 to "service", 325 to "background", 350 to "background", 400 to "cached", 1000 to "other")
        importances.forEach { (value, name) -> assertEquals(name, ExitReasons.importanceName(value)) }
    }

    @Test
    @Config(sdk = [29])
    fun `below API 30 it does nothing`() {
        val store = InMemoryTelemetryStore()
        assertEquals(0, ExitReasons.report(ApplicationProvider.getApplicationContext<Application>(), store))
        assertNull(store.getString(ExitReasons.DEFAULT_KEY))
    }

    @Test
    @Config(sdk = [34])
    fun `on API 30 and later the platform source is used without throwing`() {
        val store = InMemoryTelemetryStore()
        ExitReasons.report(ApplicationProvider.getApplicationContext<Application>(), store)
        assertEquals("0", store.getString(ExitReasons.DEFAULT_KEY))
    }
}
