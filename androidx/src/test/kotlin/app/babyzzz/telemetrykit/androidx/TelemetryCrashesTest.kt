package app.babyzzz.telemetrykit.androidx

import app.babyzzz.telemetrykit.TelemetryClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TelemetryCrashesTest {
    @get:Rule
    val folder = TemporaryFolder()

    private var original: Thread.UncaughtExceptionHandler? = null
    private var telemetry: TestTelemetry? = null
    private val order = mutableListOf<String>()
    private lateinit var pending: java.io.File

    @Before
    fun setUp() {
        original = Thread.getDefaultUncaughtExceptionHandler()
        TelemetryCrashes.resetForTest()
        pending = folder.root.resolve("pending.json")
    }

    @After
    fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(original)
        TelemetryCrashes.resetForTest()
        telemetry?.close()
    }

    private fun previous(name: String = "previous") = Thread.UncaughtExceptionHandler { _, _ -> order += name }

    private fun crash(error: Throwable = IllegalStateException("bad user@example.com")) {
        Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), error)
    }

    @Test
    fun `records the crash, persists it, then delegates to the previous handler`() {
        telemetry = TestTelemetry(storeFile = pending)
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> order += "previous:file=${pending.exists()}" }
        TelemetryCrashes.install()
        crash()
        assertEquals(listOf("previous:file=true"), order)
        val file = pending.readText()
        assertTrue(file, "IllegalStateException" in file)
        assertTrue(file, "\"where\":\"uncaught\"" in file || "uncaught" in file)
        assertTrue(file, "fatal" in file)
        assertTrue(file, "background" in file)
        assertFalse("the e-mail is scrubbed: $file", "user@example.com" in file)
        assertFalse("no stack frames: $file", "TelemetryCrashesTest" in file || "stacktrace" in file.lowercase())
    }

    @Test
    fun `the thread is main only for the main thread, never its name`() {
        telemetry = TestTelemetry(storeFile = pending)
        val named = Thread.currentThread()
        val handler = TelemetryCrashes.Handler(null, 1_000, mainThread = { named })
        handler.uncaughtException(named, RuntimeException("x"))
        assertTrue(pending.readText().contains("\"main\""))
        assertFalse(pending.readText().contains(named.name))
    }

    @Test
    fun `delegates even when recording throws`() {
        telemetry = TestTelemetry(storeFile = pending, errorKind = { error("kind failed") })
        Thread.setDefaultUncaughtExceptionHandler(previous())
        TelemetryCrashes.install()
        crash()
        assertEquals(listOf("previous"), order)
    }

    @Test
    fun `delegates when no client was started and with no previous handler`() {
        Thread.setDefaultUncaughtExceptionHandler(previous())
        TelemetryCrashes.install()
        crash()
        assertEquals(listOf("previous"), order)
        TelemetryCrashes.Handler(null, 100).uncaughtException(Thread.currentThread(), RuntimeException())
    }

    @Test
    fun `a handler that throws does not stop the previous one from running first or last`() {
        telemetry = TestTelemetry(storeFile = pending)
        Thread.setDefaultUncaughtExceptionHandler(previous("first"))
        TelemetryCrashes.install()
        val ours = Thread.getDefaultUncaughtExceptionHandler()!!
        // another crash reporter installed after ours still reaches ours, which reaches the first one
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            order += "later"
            ours.uncaughtException(t, e)
        }
        crash()
        assertEquals(listOf("later", "first"), order)
    }

    @Test
    fun `installing twice does not wrap twice`() {
        Thread.setDefaultUncaughtExceptionHandler(previous())
        TelemetryCrashes.install()
        val first = Thread.getDefaultUncaughtExceptionHandler()
        TelemetryCrashes.install()
        assertSame(first, Thread.getDefaultUncaughtExceptionHandler())
        crash()
        assertEquals(listOf("previous"), order)
    }

    @Test
    fun `nothing is recorded without consent or with the remote flag off`() {
        telemetry = TestTelemetry(storeFile = pending, consent = false)
        Thread.setDefaultUncaughtExceptionHandler(previous())
        TelemetryCrashes.install()
        crash()
        assertFalse(pending.exists())
        telemetry!!.close()
        telemetry = TestTelemetry(storeFile = pending, gate = TelemetryClient.RemoteGate.DISABLED)
        crash()
        assertFalse(pending.exists())
        assertEquals(listOf("previous", "previous"), order)
    }
}
