package app.babyzzz.telemetrykit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Session rules: 15 minutes of inactivity, no maximum length */
class TelemetrySessionTest {
    private val minute = 60_000L
    private val minutes15 = 15 * minute

    @Test
    fun `lasts until inactivity, with no cap on its length`() {
        val start = 1_000_000L
        val first = TelemetrySession.next(null, start, minutes15) { "s1" }
        assertTrue(first.isNew && first.session.id == "s1")
        val active = TelemetrySession.next(first.session, start + 14 * minute, minutes15) { "s2" }
        assertTrue(!active.isNew && active.session.id == "s1")
        val idle = TelemetrySession.next(active.session, start + 14 * minute + minutes15, minutes15) { "s3" }
        assertTrue(idle.isNew && idle.session.id == "s3")
        // kept busy, one session all night (no 4-hour cap)
        var session = first.session
        for (m in 10..12 * 60 step 10) {
            val next = TelemetrySession.next(session, start + m * minute, minutes15) { "s4" }
            session = next.session
            assertFalse(next.isNew)
        }
        assertEquals("s1", session.id)
        assertEquals(10, TelemetrySession.randomId().length)
    }

    @Test
    fun `a clock that went backwards starts a new session`() {
        val first = TelemetrySession.next(null, 1_000_000L, minutes15) { "a" }
        assertTrue(TelemetrySession.next(first.session, 999_999L, minutes15) { "b" }.isNew)
    }

    @Test
    fun `is saved and loaded, and a broken value reads as none`() {
        val store = InMemoryTelemetryStore()
        assertNull(TelemetrySession.load(store, "k"))
        val session = TelemetrySession("abc", 1, 2)
        session.save(store, "k")
        assertEquals(session, TelemetrySession.load(store, "k"))
        store.putString("k", "{broken")
        assertNull(TelemetrySession.load(store, "k"))
    }
}
