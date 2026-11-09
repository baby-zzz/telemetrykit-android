package app.babyzzz.telemetrykit

import app.babyzzz.telemetrykit.TelemetryFixtures.client
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** Configurable scrubbing: the policy per field, the presets and the per-call override */
class ScrubPolicyTest {
    @After
    fun reset() = Telemetry.install(null)

    private val raw = "a@b.com /x?token=1"
    private val clean = "[email] /x?token=[REDACTED]"
    private val redactedMail = "a@b.com"

    private val event = TelemetryItem.Event(FaroEvent("e", "android", "t", mapOf("k" to raw)))
    private val log = TelemetryItem.Log(FaroLog(raw, FaroLog.Level.WARN, "t", mapOf("category" to "c", "k" to raw)))
    private val exception = TelemetryItem.Exception(FaroException("ApiError", raw, "t", mapOf("where" to "w@x.com", "k" to raw)))
    private val measurement = TelemetryItem.Measurement(FaroMeasurement("m", mapOf("v" to 1.0), "t", mapOf("k" to raw)))

    private fun TelemetryItem.out(policy: ScrubPolicy = ScrubPolicy.ALL, scrub: Boolean? = null) = scrubbed(TelemetryScrub.default, policy, scrub)

    @Test
    fun `presets`() {
        assertEquals(ScrubPolicy(), ScrubPolicy.ALL)
        assertEquals(ScrubPolicy(false, false, false, false, false, false), ScrubPolicy.NONE)
        assertEquals(Configuration("https://c", TelemetryFixtures.app, TelemetryFixtures.browser, "p", "d", "c", "s").scrubPolicy, ScrubPolicy.ALL)
    }

    @Test
    fun `the default scrubs every free string`() {
        assertEquals(mapOf("k" to clean), (event.out() as TelemetryItem.Event).event.attributes)
        val l = (log.out() as TelemetryItem.Log).log
        assertEquals(clean, l.message)
        assertEquals(mapOf("category" to "c", "k" to clean), l.context)
        val e = (exception.out() as TelemetryItem.Exception).exception
        assertEquals(clean, e.value)
        assertEquals(mapOf("where" to "w@x.com", "k" to clean), e.context)
        assertEquals(mapOf("k" to clean), (measurement.out() as TelemetryItem.Measurement).measurement.context)
    }

    @Test
    fun `NONE sends raw values`() {
        for (item in listOf(event, log, exception, measurement)) assertEquals(item, item.out(ScrubPolicy.NONE))
    }

    @Test
    fun `each policy field works alone`() {
        val none = ScrubPolicy.NONE
        assertEquals(mapOf("k" to clean), (event.out(none.copy(eventAttributes = true)) as TelemetryItem.Event).event.attributes)
        assertEquals(event, event.out(ScrubPolicy.ALL.copy(eventAttributes = false)))

        var l = (log.out(none.copy(logMessages = true)) as TelemetryItem.Log).log
        assertEquals(clean, l.message)
        assertEquals(mapOf("category" to "c", "k" to raw), l.context)
        l = (log.out(ScrubPolicy.ALL.copy(logMessages = false)) as TelemetryItem.Log).log
        assertEquals(raw, l.message)
        assertEquals(clean, l.context!!["k"])

        l = (log.out(none.copy(logContext = true)) as TelemetryItem.Log).log
        assertEquals(raw, l.message)
        assertEquals(clean, l.context!!["k"])

        var e = (exception.out(none.copy(exceptionValues = true)) as TelemetryItem.Exception).exception
        assertEquals(clean, e.value)
        assertEquals(mapOf("where" to "w@x.com", "k" to raw), e.context)
        e = (exception.out(none.copy(exceptionContext = true)) as TelemetryItem.Exception).exception
        assertEquals(raw, e.value)
        assertEquals(mapOf("where" to "w@x.com", "k" to clean), e.context)

        assertEquals(mapOf("k" to clean), (measurement.out(none.copy(measurementContext = true)) as TelemetryItem.Measurement).measurement.context)
        assertEquals(measurement, measurement.out(ScrubPolicy.ALL.copy(measurementContext = false)))
    }

    @Test
    fun `keys the library sets are never scrubbed, other context values and the log message are`() {
        val item = TelemetryItem.Log(FaroLog("m a@b.com", FaroLog.Level.WARN, "t", mapOf("category" to "c@d.com", "other" to "a@b.com")))
        val l = (item.out(scrub = true) as TelemetryItem.Log).log
        assertEquals("m [email]", l.message)
        assertEquals(mapOf("category" to "c@d.com", "other" to "[email]"), l.context)
        val e = (exception.out(scrub = true) as TelemetryItem.Exception).exception
        assertEquals("w@x.com", e.context!!["where"])
        assertEquals(clean, e.context!!["k"])
    }

    @Test
    fun `a caller category in the context cannot replace the library's`() = runTest {
        val payload = sent(backgroundScope, ScrubPolicy.ALL) {
            Telemetry.log(FaroLog.Level.WARN, "real", "m", mapOf("category" to "a@b.com", "k" to "v"))
        }
        assertEquals(mapOf("category" to "real", "k" to "v"), payload.logs!!.single().context)
        assertEquals(mapOf("category" to "c", "k" to "v"), Telemetry.logContext(mapOf("category" to "x", "k" to "v"), "c"))
    }

    @Test
    fun `only free text changes`() {
        val e = (exception.out(scrub = true) as TelemetryItem.Exception).exception
        assertEquals("ApiError", e.type)
        assertEquals("t", e.timestamp)
        val l = (log.out(scrub = true) as TelemetryItem.Log).log
        assertEquals(FaroLog.Level.WARN, l.level)
        assertEquals("t", l.timestamp)
        assertEquals("e", (event.out(scrub = true) as TelemetryItem.Event).event.name)
    }

    @Test
    fun `a per-call override beats the policy`() {
        for (item in listOf(event, log, exception, measurement)) {
            assertEquals(item.out(ScrubPolicy.ALL), item.out(ScrubPolicy.NONE, scrub = true))
            assertEquals(item, item.out(ScrubPolicy.ALL, scrub = false))
            assertEquals(item.out(ScrubPolicy.NONE, scrub = true), item.out(ScrubPolicy.NONE.copy(logMessages = true), scrub = true))
        }
        assertEquals(clean, ((log.out(ScrubPolicy.NONE, scrub = true)) as TelemetryItem.Log).log.message)
    }

    @Test
    fun `scrubbing that runs still fails closed`() {
        val boom = TelemetryScrub(extraPatterns = listOf(Regex("(x|y)*")))
        val long = "xy".repeat(100_000)
        val item = TelemetryItem.Event(FaroEvent("e", "android", "t", mapOf("k" to long)))
        assertEquals(mapOf("k" to TelemetryScrub.REDACTED), (item.scrubbed(boom) as TelemetryItem.Event).event.attributes)
        assertEquals(mapOf("k" to long), (item.scrubbed(boom, ScrubPolicy.NONE) as TelemetryItem.Event).event.attributes)
        assertEquals(mapOf("k" to TelemetryScrub.REDACTED), (item.scrubbed(boom, ScrubPolicy.NONE, scrub = true) as TelemetryItem.Event).event.attributes)
    }

    private suspend fun sent(
        scope: kotlinx.coroutines.CoroutineScope,
        policy: ScrubPolicy,
        record: () -> Unit,
    ): FaroPayload {
        val transport = RecordingTransport()
        val c = client(scope, transport, remote = TelemetryClient.RemoteGate.ENABLED, configuration = TelemetryFixtures.configuration(scrubPolicy = policy))
        Telemetry.install(c)
        record()
        c.flush()
        return transport.payloads().single()
    }

    @Test
    fun `the facade passes the policy and the per-call override through`() = runTest {
        val ctx = mapOf("k" to raw)
        val all = sent(backgroundScope, ScrubPolicy.ALL) {
            Telemetry.event("e", ctx)
            Telemetry.log(FaroLog.Level.ERROR, "c", raw, ctx)
            Telemetry.error(IllegalStateException("a@b.com"), "w")
            Telemetry.measurement("m", mapOf("v" to 1.0), ctx)
        }
        assertEquals(clean, all.events!!.single { it.name == "e" }.attributes!!["k"])
        assertEquals(clean, all.logs!!.single().message)
        assertEquals("IllegalStateException: [email]", all.exceptions!!.single().value)
        assertEquals(clean, all.measurements!!.single().context!!["k"])

        val off = sent(backgroundScope, ScrubPolicy.ALL) {
            Telemetry.event("e", ctx, scrub = false)
            Telemetry.log(FaroLog.Level.ERROR, "c", raw, ctx, scrub = false)
            Telemetry.error(IllegalStateException("a@b.com"), "w", scrub = false)
            Telemetry.measurement("m", mapOf("v" to 1.0), ctx, scrub = false)
        }
        assertEquals(raw, off.events!!.single { it.name == "e" }.attributes!!["k"])
        assertEquals(raw, off.logs!!.single().message)
        assertEquals("IllegalStateException: $redactedMail", off.exceptions!!.single().value)
        assertEquals(raw, off.measurements!!.single().context!!["k"])

        val on = sent(backgroundScope, ScrubPolicy.NONE) {
            Telemetry.event("e", ctx, scrub = true)
            Telemetry.eventThrottled("t", ctx, scrub = true)
            Telemetry.log(FaroLog.Level.ERROR, "c", raw, ctx, scrub = true)
            Telemetry.error(IllegalStateException("a@b.com"), "w", scrub = true)
            Telemetry.measurement("m", mapOf("v" to 1.0), ctx, scrub = true)
        }
        assertEquals(clean, on.events!!.single { it.name == "e" }.attributes!!["k"])
        assertEquals(clean, on.events!!.single { it.name == "t" }.attributes!!["k"])
        assertEquals(clean, on.logs!!.single().message)
        assertEquals("IllegalStateException: [email]", on.exceptions!!.single().value)
        assertEquals(clean, on.measurements!!.single().context!!["k"])

        val none = sent(backgroundScope, ScrubPolicy.NONE) {
            Telemetry.event("e", ctx)
            Telemetry.log(FaroLog.Level.ERROR, "c", raw, ctx)
            Telemetry.error(IllegalStateException("a@b.com"), "w")
            Telemetry.measurement("m", mapOf("v" to 1.0), ctx)
        }
        assertEquals(raw, none.events!!.single { it.name == "e" }.attributes!!["k"])
        assertEquals(raw, none.logs!!.single().message)
        assertEquals("IllegalStateException: $redactedMail", none.exceptions!!.single().value)
        assertEquals(raw, none.measurements!!.single().context!!["k"])
    }
}
