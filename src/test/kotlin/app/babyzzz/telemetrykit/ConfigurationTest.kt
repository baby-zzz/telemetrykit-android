package app.babyzzz.telemetrykit

import app.babyzzz.telemetrykit.TelemetryClient.RemoteGate
import app.babyzzz.telemetrykit.TelemetryFixtures.client
import app.babyzzz.telemetrykit.TelemetryFixtures.configuration
import app.babyzzz.telemetrykit.TelemetryFixtures.event
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/** What the host can configure: identity, store keys, limits, the error hook and the scrub */
@OptIn(ExperimentalCoroutinesApi::class)
class ConfigurationTest {
    @Test
    fun `the sdk defaults to this library and its version`() {
        assertEquals("telemetrykit-android", configuration().sdk.name)
        assertEquals(System.getProperty("telemetrykit.version"), TelemetryKitInfo.VERSION)
    }

    @Test
    fun `the sdk, platform and domain are sent as configured`() = runTest {
        val transport = RecordingTransport()
        val custom = configuration(platform = "tv", domain = "set-top", sdk = FaroMeta.Sdk("my-wrapper", "9"))
        val client = client(backgroundScope, transport, remote = RemoteGate.ENABLED, configuration = custom)
        client.record(TelemetryItem.Event(FaroEvent("x", custom.domain, "t", null)))
        client.flush()
        val payload = transport.payloads().single()
        assertEquals(FaroMeta.Sdk("my-wrapper", "9"), payload.meta.sdk)
        assertEquals("tv", payload.meta.session?.attributes?.get("platform"))
        assertEquals(listOf("set-top", "set-top"), payload.events?.map { it.domain })
    }

    @Test
    fun `the consent and session keys name the store entries`() = runTest {
        val store = InMemoryTelemetryStore()
        val config = configuration(consentKey = "my_consent", sessionKey = "my_session")
        val client = client(backgroundScope, store = store, configuration = config)
        client.record(event("a"))
        assertNotNull(store.getString("my_session"))
        assertNull(store.getString("session"))
        client.setConsent(false)
        assertEquals(false, store.getBoolean("my_consent"))
        assertFalse(Telemetry.consent(store, config))
        assertFalse(Telemetry.consent(InMemoryTelemetryStore(), config))
    }

    @Test
    fun `the batch size is configurable`() = runTest {
        val transport = RecordingTransport()
        val client = client(backgroundScope, transport, remote = RemoteGate.ENABLED, configuration = configuration(maxBatch = 3))
        repeat(6) { client.record(event("e$it")) } // + session_start = 7
        client.flush()
        assertEquals(listOf(3, 3, 1), transport.payloads().map { it.events.orEmpty().size })
    }

    @Test
    fun `the backoff steps are configurable`() = runTest {
        val clock = FakeClock()
        val transport = RecordingTransport()
        val config = configuration(backoffMillis = listOf(1_000, 2_000))
        val client = client(backgroundScope, transport, remote = RemoteGate.ENABLED, clock = clock, configuration = config)
        client.record(event("a"))
        for ((index, wait) in listOf(1_000L, 2_000L, 2_000L).withIndex()) {
            transport.respond(TelemetryResponse(500))
            assertFalse(client.flush())
            clock.millis += wait - 1
            assertTrue(client.flush())
            assertEquals(index + 1, transport.bodies.size)
            clock.millis += 1
        }
    }

    @Test
    fun `the session inactivity timeout is configurable`() = runTest {
        val clock = FakeClock()
        val client = client(backgroundScope, clock = clock, configuration = configuration(sessionInactivityMillis = 60_000))
        client.record(event("a"))
        val first = client.currentSession?.id
        clock.millis += 59_999
        client.record(event("b"))
        assertEquals(first, client.currentSession?.id)
        clock.millis += 60_000
        client.record(event("c"))
        assertTrue(first != client.currentSession?.id)
    }

    @Test
    fun `the stored age limit and pending cap are configurable`() = runTest {
        val clock = FakeClock()
        val config = configuration(maxStoredAgeMillis = 1_000, maxPending = 4)
        val file = File.createTempFile("pending", ".json").apply { deleteOnExit() }
        val first = client(backgroundScope, storeFile = file, clock = clock, configuration = config)
        repeat(10) { first.record(event("e$it")) }
        assertEquals(4, first.pendingCount)
        first.persist()
        clock.millis += 1_001
        val later = client(backgroundScope, storeFile = file, clock = clock, configuration = config)
        later.restore()
        assertEquals(0, later.pendingCount)
    }

    @Test
    fun `the error kind hook and the length limit apply`() {
        val config = configuration(maxErrorValueLength = 20, errorKind = { e -> "kind_" + e.javaClass.simpleName.length })
        assertEquals("kind_21: boom", config.errorValue(IllegalStateException("boom")))
        assertEquals(20, config.errorValue(IllegalStateException("x".repeat(100))).length)
        assertEquals("kind_21", config.errorValue(IllegalStateException(" ")))
    }

    @Test
    fun `the default error kind is the simple class name`() {
        class Local : Exception("m")
        assertEquals("Local: m", configuration().errorValue(Local()))
        assertEquals("Throwable: m", configuration().errorValue(object : Exception("m") {}))
    }

    @Test
    fun `extra scrub parameters and patterns apply on top of the defaults`() {
        val scrub = configuration(
            extraScrubParameters = listOf("invite", "a.b"),
            extraScrubPatterns = listOf(Regex("""\b\d{3}-\d{2}-\d{4}\b""")),
        ).scrub
        assertEquals("/x?invite=[REDACTED]&token=[REDACTED]&n=1", scrub.text("/x?invite=abc&token=t&n=1"))
        assertEquals("/x?a.b=[REDACTED]", scrub.text("/x?a.b=1"))
        assertEquals("/x?aXb=1", scrub.text("/x?aXb=1")) // parameter names are literal, not regexes
        assertEquals("ssn [REDACTED] ok", scrub.text("ssn 123-45-6789 ok"))
        assertEquals("a [email]", scrub.text("a x@y.com"))
    }

    @Test
    fun `the client scrubs with the configured extras`() = runTest {
        val transport = RecordingTransport()
        val config = configuration(extraScrubParameters = listOf("invite"))
        val client = client(backgroundScope, transport, remote = RemoteGate.ENABLED, configuration = config)
        client.record(TelemetryItem.Event(FaroEvent("e", "d", "t", mapOf("u" to "/x?invite=abc"))))
        client.flush()
        assertEquals("/x?invite=[REDACTED]", transport.payloads().single().events?.last()?.attributes?.get("u"))
    }

    @Test
    fun `invalid values are refused`() {
        for (invalid in listOf({ configuration(backoffMillis = emptyList()) }, { configuration(maxBatch = 0) }, { configuration(maxErrorValueLength = 0) })) {
            try {
                invalid()
                fail("accepted")
            } catch (_: IllegalArgumentException) {
            }
        }
        try {
            Configuration(" ", TelemetryFixtures.app, TelemetryFixtures.browser, "p", "d", "c", "s")
            fail("accepted a blank endpoint")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun `the in-memory store round-trips`() {
        val store = InMemoryTelemetryStore(mapOf("a" to "1"))
        assertEquals("1", store.getString("a"))
        store.putBoolean("b", true)
        assertEquals(true, store.getBoolean("b"))
        store.remove("a")
        assertNull(store.getString("a"))
        assertEquals(mapOf("b" to "true"), store.values)
    }
}
