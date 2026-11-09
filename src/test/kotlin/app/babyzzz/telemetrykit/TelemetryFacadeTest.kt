package app.babyzzz.telemetrykit

import app.babyzzz.telemetrykit.TelemetryClient.RemoteGate
import app.babyzzz.telemetrykit.TelemetryFixtures.client
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The facade: gates of the build and the user, the shape of what it records, and that it never throws */
class TelemetryFacadeTest {
    @After
    fun reset() = Telemetry.install(null)

    @Test
    fun `debug builds send only when asked`() {
        assertTrue(Telemetry.buildAllows(debug = false, debugSwitch = false))
        assertFalse(Telemetry.buildAllows(debug = true, debugSwitch = false))
        assertTrue(Telemetry.buildAllows(debug = true, debugSwitch = true))
    }

    @Test
    fun `a debug build without the switch starts nothing`() {
        val started = Telemetry.start(TelemetryFixtures.configuration(), InMemoryTelemetryStore(), null, debug = true, debugSwitch = false)
        assertFalse(started)
        assertNull(Telemetry.client)
        Telemetry.event("ignored") // a no-op, no throw
    }

    @Test
    fun `consent defaults to off, or on for an opt-out host, until the user chooses`() = runTest {
        val store = InMemoryTelemetryStore()
        assertFalse(Telemetry.consent(store, TelemetryFixtures.configuration()))
        assertTrue(Telemetry.consent(store, TelemetryFixtures.configuration(consentDefault = true)))
        Telemetry.install(client(backgroundScope, store = store, remote = RemoteGate.ENABLED))
        Telemetry.event("a")
        assertEquals(2, Telemetry.client?.pendingCount)
        Telemetry.setConsent(false)
        assertFalse(Telemetry.consent(store, TelemetryFixtures.configuration()))
        assertEquals("false", store.getString(TelemetryFixtures.CONSENT_KEY))
        assertEquals(0, Telemetry.client?.pendingCount)
    }

    @Test
    fun `remote flag maps null true false to the gates`() = runTest {
        val client = client(backgroundScope)
        Telemetry.install(client)
        Telemetry.event("a")
        Telemetry.setRemoteEnabled(true)
        assertTrue(client.isOpen)
        Telemetry.setRemoteEnabled(null)
        assertFalse(client.isOpen)
        assertEquals(2, client.pendingCount) // unknown keeps the queue
        Telemetry.setRemoteEnabled(false)
        assertEquals(0, client.pendingCount)
    }

    @Test
    fun `records the shapes the dashboards expect`() = runTest {
        val transport = RecordingTransport()
        val client = client(backgroundScope, transport, remote = RemoteGate.ENABLED)
        Telemetry.install(client)
        Telemetry.setUser("sub-1")
        Telemetry.setView("monitor")
        Telemetry.event("thing", mapOf("a" to "b"))
        Telemetry.measurement("app_launch", mapOf("cold_ms" to 1.0))
        Telemetry.log(FaroLog.Level.WARN, "auth", "Token refresh refused, signing out", mapOf("k" to "v"))
        client.flush()
        val payload = transport.payloads().single()
        assertEquals("sub-1", payload.meta.user?.id)
        assertEquals("monitor", payload.meta.view?.name)
        assertEquals(listOf("session_start", "thing"), payload.events?.map { it.name })
        assertEquals(mapOf("a" to "b"), payload.events?.last()?.attributes)
        assertEquals("app_launch", payload.measurements?.single()?.type)
        val log = payload.logs?.single()
        assertEquals(mapOf("category" to "auth", "k" to "v"), log?.context)
        assertEquals(FaroLog.Level.WARN, log?.level)
    }

    @Test
    fun `an error carries its type, kind and scrubbed message, never a stack`() = runTest {
        val transport = RecordingTransport()
        val client = client(backgroundScope, transport, remote = RemoteGate.ENABLED)
        Telemetry.install(client)
        class Boom(override val message: String) : Exception(message)
        Telemetry.error(Boom("failed https://x/?token=abc for a@b.com"), "alert")
        client.flush()
        val exception = transport.payloads().single().exceptions?.single()
        assertEquals("Boom", exception?.type)
        assertEquals("Boom: failed https://x/?token=[REDACTED] for [email]", exception?.value)
        assertEquals(mapOf("where" to "alert"), exception?.context)
        assertFalse(transport.bodies.single().contains("abc"))
        assertFalse(transport.bodies.single().contains("a@b.com"))
        val configuration = TelemetryFixtures.configuration()
        assertEquals(500, configuration.errorValue(Boom("x".repeat(2000))).length)
        assertEquals("Boom", configuration.errorValue(Boom("  ")))
    }

    @Test
    fun `a broken client never throws into the caller`() = runTest {
        val throwing = object : TelemetryTransport {
            override suspend fun post(body: ByteArray): TelemetryResponse = error("boom")
        }
        Telemetry.install(client(backgroundScope, throwing, remote = RemoteGate.ENABLED))
        Telemetry.event("a")
        Telemetry.onStop()
        Telemetry.onStart()
        Telemetry.setUser(null)
    }

}
