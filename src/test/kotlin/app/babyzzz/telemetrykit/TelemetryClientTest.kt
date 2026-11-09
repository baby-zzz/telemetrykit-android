package app.babyzzz.telemetrykit

import app.babyzzz.telemetrykit.TelemetryClient.RemoteGate
import app.babyzzz.telemetrykit.TelemetryFixtures.client
import app.babyzzz.telemetrykit.TelemetryFixtures.event
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

/**
 * The queue's gates, batching, retries and offline file. The clock is injected and `flushDelayMillis` is an hour, so no
 * timed flush fires: only an explicit `flush()` sends (except where a test advances virtual time on purpose).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TelemetryClientTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `nothing is sent until the flag says yes`() = runTest {
        val transport = RecordingTransport()
        val client = client(backgroundScope, transport)
        client.record(event("a"))
        assertEquals(2, client.pendingCount) // session_start + a, held in memory
        assertTrue(client.flush())
        assertTrue(transport.bodies.isEmpty())
        client.setRemote(RemoteGate.DISABLED)
        assertEquals(0, client.pendingCount)
        client.record(event("b"))
        assertEquals(0, client.pendingCount)
    }

    @Test
    fun `an enabled flag sends what was held`() = runTest {
        val transport = RecordingTransport()
        val client = client(backgroundScope, transport)
        client.record(event("a"))
        client.setRemote(RemoteGate.ENABLED)
        assertTrue(client.flush())
        assertEquals(listOf("session_start", "a"), transport.payloads().flatMap { it.events.orEmpty() }.map { it.name })
    }

    @Test
    fun `consent off drops the queue and records nothing`() = runTest {
        val transport = RecordingTransport()
        val client = client(backgroundScope, transport, remote = RemoteGate.ENABLED)
        client.record(event("a"))
        client.setConsent(false)
        assertEquals(0, client.pendingCount)
        client.record(event("b"))
        client.flush()
        assertTrue(transport.bodies.isEmpty())
        // and back on: recording resumes
        client.setConsent(true)
        client.record(event("c"))
        assertTrue(client.pendingCount > 0)
    }

    @Test
    fun `consent off deletes the pending file`() = runTest {
        val file = folder.root.resolve("telemetry/pending.json")
        val client = client(backgroundScope, storeFile = file)
        client.record(event("a"))
        client.persist()
        assertTrue(file.exists())
        client.setConsent(false)
        assertFalse(file.exists())
    }

    @Test
    fun `a flag that turns off deletes the file too`() = runTest {
        val file = folder.root.resolve("pending.json")
        val client = client(backgroundScope, storeFile = file)
        client.record(event("a"))
        client.persist()
        client.setRemote(RemoteGate.DISABLED)
        assertFalse(file.exists())
    }

    @Test
    fun `becoming active starts a session without other items`() = runTest {
        val transport = RecordingTransport()
        val clock = FakeClock()
        val client = client(backgroundScope, transport, remote = RemoteGate.ENABLED, clock = clock)
        client.touchSession()
        client.touchSession() // same session: nothing more
        assertEquals(1, client.pendingCount)
        assertTrue(client.flush())
        assertEquals(listOf("session_start"), transport.payloads().single().events?.map { it.name })
        val first = transport.payloads().single().meta.session?.id

        clock.millis += 10 * 60_000 // active within the timeout: same session, no event
        client.touchSession()
        assertEquals(0, client.pendingCount)
        clock.millis += 14 * 60_000 // 14 min after the last touch: still running (the touch extended it)
        client.record(event("e"))
        assertTrue(client.flush())
        assertEquals(listOf("e"), transport.payloads().last().events?.map { it.name })
        assertEquals(first, transport.payloads().last().meta.session?.id)

        clock.millis += 16 * 60_000 // after the timeout: a new session
        client.touchSession()
        assertTrue(client.flush())
        assertEquals(listOf("session_start"), transport.payloads().last().events?.map { it.name })
        assertFalse(first == transport.payloads().last().meta.session?.id)
    }

    @Test
    fun `no session without consent or with the flag off`() = runTest {
        val off = client(backgroundScope, remote = RemoteGate.DISABLED)
        off.touchSession()
        assertEquals(0, off.pendingCount)
        val noConsent = client(backgroundScope, remote = RemoteGate.ENABLED, consent = false)
        noConsent.touchSession()
        assertEquals(0, noConsent.pendingCount)
    }

    @Test
    fun `sends one request per meta with session_start first`() = runTest {
        val transport = RecordingTransport()
        val client = client(backgroundScope, transport, remote = RemoteGate.ENABLED)
        client.record(event("before"))
        client.setUser("sub-1")
        client.record(event("after"))
        assertTrue(client.flush())
        val payloads = transport.payloads()
        assertEquals(2, payloads.size)
        assertEquals(listOf("session_start", "before"), payloads[0].events?.map { it.name })
        assertNull(payloads[0].meta.user)
        assertEquals(listOf("after"), payloads[1].events?.map { it.name })
        assertEquals("sub-1", payloads[1].meta.user?.id)
        assertTrue(payloads.all { it.meta.session?.id == payloads[0].meta.session?.id })
        assertEquals("android", payloads[0].meta.session?.attributes?.get("platform"))
        assertEquals(0, client.pendingCount)
    }

    @Test
    fun `batches of at most maxBatch`() = runTest {
        val transport = RecordingTransport()
        val client = client(backgroundScope, transport, remote = RemoteGate.ENABLED)
        repeat(119) { client.record(event("e$it")) } // + session_start = 120
        assertTrue(client.flush())
        // let the size-triggered flush (scheduled at 50 items) settle too
        runCurrent()
        assertEquals(listOf(50, 50, 20), transport.payloads().map { it.events.orEmpty().size })
        assertEquals(0, client.pendingCount)
    }

    @Test
    fun `the timer flushes after the flush delay`() = runTest {
        val transport = RecordingTransport()
        val configuration = TelemetryFixtures.configuration(flushDelayMillis = 5_000)
        val client = TelemetryClient(configuration, transport, InMemoryTelemetryStore(), null, true, backgroundScope, FakeClock().now)
        client.setRemote(RemoteGate.ENABLED)
        runCurrent() // the immediate flush the gate opening schedules finds nothing
        client.record(event("a"))
        runCurrent()
        assertTrue(transport.bodies.isEmpty())
        advanceTimeBy(5_001)
        runCurrent()
        assertEquals(1, transport.bodies.size)
    }

    @Test
    fun `server errors are retried and refusals dropped`() = runTest {
        // A fixed clock: the 5 s backoff must not run out however slowly the test runs
        val clock = FakeClock()
        val transport = RecordingTransport()
        val client = client(backgroundScope, transport, remote = RemoteGate.ENABLED, clock = clock)
        transport.respond(TelemetryResponse(503))
        client.record(event("a"))
        assertFalse(client.flush())
        assertEquals(2, client.pendingCount) // back in the queue, waiting for the backoff
        assertTrue(client.flush()) // still within the backoff: nothing sent
        assertEquals(1, transport.bodies.size)
        // the backoff ends (injected clock): it is sent again, and a 400 is not retried
        clock.millis += 5_000
        transport.respond(TelemetryResponse(400))
        assertTrue(client.flush())
        assertEquals(2, transport.bodies.size)
        assertEquals(0, client.pendingCount)
    }

    @Test
    fun `backoff grows 5 30 120 600 seconds and honours Retry-After`() = runTest {
        val clock = FakeClock()
        val transport = RecordingTransport()
        val client = client(backgroundScope, transport, remote = RemoteGate.ENABLED, clock = clock)
        client.record(event("a"))
        val waits = listOf(5_000L, 30_000L, 120_000L, 600_000L, 600_000L)
        for ((index, wait) in waits.withIndex()) {
            transport.respond(TelemetryResponse(500))
            assertFalse(client.flush())
            assertEquals(index + 1, transport.bodies.size)
            clock.millis += wait - 1
            assertTrue(client.flush()) // 1 ms short: nothing sent
            assertEquals(index + 1, transport.bodies.size)
            clock.millis += 1
        }
        transport.respond(TelemetryResponse(429, retryAfterSeconds = 900))
        assertFalse(client.flush())
        clock.millis += 899_999
        client.flush()
        assertEquals(waits.size + 1, transport.bodies.size)
        clock.millis += 1
        transport.respond(TelemetryResponse(204))
        assertTrue(client.flush())
        assertEquals(waits.size + 2, transport.bodies.size)
        assertEquals(0, client.pendingCount)
    }

    @Test
    fun `a transport that throws keeps the batch`() = runTest {
        val transport = RecordingTransport().apply { failure = IOException("offline") }
        val client = client(backgroundScope, transport, remote = RemoteGate.ENABLED)
        client.record(event("a"))
        assertFalse(client.flush())
        assertEquals(2, client.pendingCount)
    }

    @Test
    fun `a flush during another waits for it and sends what it missed`() = runTest {
        val transport = RecordingTransport()
        val gate = CompletableDeferred<Unit>()
        transport.gate = gate
        val client = client(backgroundScope, transport, remote = RemoteGate.ENABLED)
        client.record(event("first"))
        val one = async { client.flush() }
        runCurrent()
        assertEquals(1, transport.bodies.size) // the first drain is inside the request
        client.record(event("second"))
        val two = async { client.flush() }
        runCurrent()
        assertEquals(1, transport.bodies.size) // only one request at a time
        assertFalse(two.isCompleted)
        gate.complete(Unit)
        assertTrue(one.await())
        assertTrue(two.await())
        assertEquals(listOf("session_start", "first", "second"), transport.payloads().flatMap { it.events.orEmpty() }.map { it.name })
        assertEquals(0, client.pendingCount)
    }

    @Test
    fun `throttled events count what was suppressed`() = runTest {
        val clock = FakeClock()
        val client = client(backgroundScope, clock = clock)
        val event = FaroEvent("token_refresh_failed", "android", "t", mapOf("reason" to "x"))
        assertTrue(client.recordThrottled(event, "k", 60_000))
        assertFalse(client.recordThrottled(event, "k", 60_000))
        assertFalse(client.recordThrottled(event, "k", 60_000))
        clock.millis += 61_000
        assertTrue(client.recordThrottled(event, "k", 60_000))
        assertEquals(3, client.pendingCount) // session_start + two sent
        client.setRemote(RemoteGate.ENABLED)
    }

    @Test
    fun `the throttle carries the suppressed count`() = runTest {
        val clock = FakeClock()
        val transport = RecordingTransport()
        val client = client(backgroundScope, transport, remote = RemoteGate.ENABLED, clock = clock)
        val event = FaroEvent("e", "android", "t", mapOf("reason" to "x"))
        client.recordThrottled(event, "k", 60_000)
        client.recordThrottled(event, "k", 60_000)
        clock.millis += 61_000
        client.recordThrottled(event, "k", 60_000)
        client.flush()
        val sent = transport.payloads().flatMap { it.events.orEmpty() }.filter { it.name == "e" }
        assertEquals(listOf("0", "1"), sent.map { it.attributes?.get("suppressed") })
    }

    @Test
    fun `the queue is capped at maxPending, oldest dropped`() = runTest {
        val client = client(backgroundScope)
        repeat(600) { client.record(event("e$it")) }
        assertEquals(500, client.pendingCount)
    }

    @Test
    fun `what could not be sent is kept for the next launch`() = runTest {
        val file = folder.root.resolve("telemetry/pending.json")
        val first = client(backgroundScope, storeFile = file)
        first.record(event("offline"))
        first.persist()
        assertTrue(file.exists())

        val transport = RecordingTransport()
        val next = client(backgroundScope, transport, storeFile = file, remote = RemoteGate.ENABLED)
        next.restore()
        assertFalse(file.exists())
        assertEquals(2, next.pendingCount)
        assertTrue(next.flush())
        assertEquals(listOf("session_start", "offline"), transport.payloads().flatMap { it.events.orEmpty() }.map { it.name })
    }

    @Test
    fun `a restored file goes ahead of new items`() = runTest {
        val file = folder.root.resolve("pending.json")
        val first = client(backgroundScope, storeFile = file)
        first.record(event("old"))
        first.persist()
        val transport = RecordingTransport()
        val next = client(backgroundScope, transport, storeFile = file, remote = RemoteGate.ENABLED)
        next.record(event("new"))
        next.restore()
        next.flush()
        val names = transport.payloads().flatMap { it.events.orEmpty() }.map { it.name }
        assertEquals(listOf("session_start", "old", "session_start", "new"), names)
    }

    @Test
    fun `stored items older than a day are dropped`() = runTest {
        val file = folder.root.resolve("pending.json")
        val clock = FakeClock()
        val old = client(backgroundScope, storeFile = file, clock = clock)
        old.record(event("stale"))
        old.persist()
        clock.millis += 25 * 60 * 60 * 1000L
        val later = client(backgroundScope, storeFile = file, clock = clock)
        later.restore()
        assertEquals(0, later.pendingCount)
        assertFalse(file.exists())
    }

    @Test
    fun `the file is capped in size, oldest dropped`() = runTest {
        val file = folder.root.resolve("pending.json")
        val configuration = TelemetryFixtures.configuration(maxStoredBytes = 6_000)
        val client = TelemetryClient(configuration, RecordingTransport(), InMemoryTelemetryStore(), file, true, backgroundScope, FakeClock().now)
        repeat(100) { client.record(event("e$it")) }
        client.persist()
        assertTrue(file.length() in 1..6_000)
        val next = client(backgroundScope, storeFile = file)
        next.restore()
        assertTrue(next.pendingCount in 1 until 100)
    }

    @Test
    fun `a corrupt file is ignored and removed`() = runTest {
        val file = folder.root.resolve("pending.json")
        file.writeText("{not json")
        val client = client(backgroundScope, storeFile = file)
        client.restore()
        assertEquals(0, client.pendingCount)
        assertFalse(file.exists())
    }

    @Test
    fun `leaving writes the file first, then sends, and a success removes it`() = runTest {
        val file = folder.root.resolve("pending.json")
        val transport = RecordingTransport()
        val gate = CompletableDeferred<Unit>()
        transport.gate = gate
        val client = client(backgroundScope, transport, storeFile = file, remote = RemoteGate.ENABLED)
        client.record(event("a"))
        val stop = async { client.persistAndFlush() }
        runCurrent()
        assertTrue(file.exists()) // written before the request finished
        assertEquals(1, transport.bodies.size)
        gate.complete(Unit)
        stop.await()
        assertFalse(file.exists())
        assertEquals(0, client.pendingCount)
    }

    @Test
    fun `leaving while offline keeps the file`() = runTest {
        val file = folder.root.resolve("pending.json")
        val transport = RecordingTransport().apply { failure = IOException("offline") }
        val client = client(backgroundScope, transport, storeFile = file, remote = RemoteGate.ENABLED)
        client.record(event("a"))
        client.persistAndFlush()
        assertTrue(file.exists())
        assertEquals(2, client.pendingCount)
        client.foregrounded() // back: the queue is in memory, the copy goes
        assertFalse(file.exists())
    }

    @Test
    fun `disabled means zero requests`() = runTest {
        val transport = RecordingTransport()
        val client = client(backgroundScope, transport, remote = RemoteGate.DISABLED)
        client.record(event("a"))
        client.persistAndFlush()
        runCurrent()
        assertTrue(transport.bodies.isEmpty())
    }

    @Test
    fun `the session is persisted and continues across launches`() = runTest {
        val store = InMemoryTelemetryStore()
        val first = client(backgroundScope, store = store)
        first.record(event("a"))
        val id = first.currentSession?.id
        val second = client(backgroundScope, store = store)
        second.record(event("b"))
        assertEquals(id, second.currentSession?.id)
        assertEquals(1, second.pendingCount) // no second session_start
    }
}
