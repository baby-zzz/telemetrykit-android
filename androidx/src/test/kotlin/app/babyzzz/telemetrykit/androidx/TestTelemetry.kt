package app.babyzzz.telemetrykit.androidx

import app.babyzzz.telemetrykit.Configuration
import app.babyzzz.telemetrykit.FaroEvent
import app.babyzzz.telemetrykit.FaroException
import app.babyzzz.telemetrykit.FaroMeta
import app.babyzzz.telemetrykit.FaroPayload
import app.babyzzz.telemetrykit.InMemoryTelemetryStore
import app.babyzzz.telemetrykit.Telemetry
import app.babyzzz.telemetrykit.TelemetryClient
import app.babyzzz.telemetrykit.TelemetryResponse
import app.babyzzz.telemetrykit.TelemetryTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.io.File

/** A client on a fake transport, installed as the core's global; flushes only when asked (the delay is an hour) */
class TestTelemetry(
    storeFile: File? = null,
    consent: Boolean = true,
    gate: TelemetryClient.RemoteGate = TelemetryClient.RemoteGate.ENABLED,
    errorKind: (Throwable) -> String = { it::class.java.simpleName.ifEmpty { "Throwable" } },
) : AutoCloseable {
    val bodies = mutableListOf<String>()
    private val transport = TelemetryTransport { body ->
        bodies += body.decodeToString()
        TelemetryResponse(204)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val client = TelemetryClient(
        Configuration(
            endpoint = "https://collector.example.com/collect",
            app = FaroMeta.App("my-app", "android", "1.0+1", "development", "com.example.app"),
            browser = FaroMeta.Browser("MyApp-Android", "1.0", "Android 16", true, "en"),
            platform = "android",
            domain = "android",
            consentKey = "consent",
            sessionKey = "session",
            flushDelayMillis = 3_600_000,
            errorKind = errorKind,
        ),
        transport,
        InMemoryTelemetryStore(),
        storeFile,
        consent,
        scope,
    ).also {
        it.setRemote(gate)
        Telemetry.install(it)
    }

    private fun payloads(): List<FaroPayload> {
        runBlocking { client.flush() }
        return bodies.map { FaroPayload.json.decodeFromString(FaroPayload.serializer(), it) }
    }

    /** Everything recorded so far, sent through the fake transport */
    fun events(): List<FaroEvent> = payloads().flatMap { it.events.orEmpty() }

    fun exceptions(): List<FaroException> = payloads().flatMap { it.exceptions.orEmpty() }

    fun viewNames(): List<String?> = payloads().flatMap { p -> p.events.orEmpty().map { p.meta.view?.name } }

    override fun close() {
        Telemetry.install(null)
        scope.cancel()
    }
}
