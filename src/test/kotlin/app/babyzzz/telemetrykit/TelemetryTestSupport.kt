package app.babyzzz.telemetrykit

import kotlinx.coroutines.CoroutineScope
import java.io.File

/** A settable clock: backoff and sessions in tests never read the wall clock */
class FakeClock(var millis: Long = 1_000_000) {
    val now: () -> Long = { millis }
}

/** Records request bodies; answers 204 unless told otherwise; [gate] holds the answer until released */
class RecordingTransport : TelemetryTransport {
    val bodies = mutableListOf<String>()
    private val statuses = ArrayDeque<TelemetryResponse>()
    var gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
    var failure: Exception? = null

    fun respond(vararg responses: TelemetryResponse) {
        statuses.clear()
        statuses.addAll(responses)
    }

    override suspend fun post(body: ByteArray): TelemetryResponse {
        bodies += body.decodeToString()
        gate?.await()
        failure?.let { throw it }
        return statuses.removeFirstOrNull() ?: TelemetryResponse(204)
    }

    fun payloads(): List<FaroPayload> = bodies.map { FaroPayload.json.decodeFromString(FaroPayload.serializer(), it) }
}

object TelemetryFixtures {
    val app = FaroMeta.App("my-app", "android", "1.0+1", "development", "com.example.app")
    val browser = FaroMeta.Browser("MyApp-Android", "1.0", "Android 16", true, "en")
    const val CONSENT_KEY = "consent"
    const val SESSION_KEY = "session"

    fun configuration(
        flushDelayMillis: Long = 3_600_000,
        maxBatch: Int = 50,
        maxPending: Int = 500,
        maxStoredBytes: Int = 512 * 1024,
        maxStoredAgeMillis: Long = 24 * 60 * 60 * 1000L,
        backoffMillis: List<Long> = listOf(5_000, 30_000, 120_000, 600_000),
        sessionInactivityMillis: Long = 15 * 60 * 1000L,
        maxErrorValueLength: Int = 500,
        extraScrubParameters: List<String> = emptyList(),
        extraScrubPatterns: List<Regex> = emptyList(),
        errorKind: (Throwable) -> String = { it::class.java.simpleName.ifEmpty { "Throwable" } },
        domain: String = "android",
        platform: String = "android",
        consentKey: String = CONSENT_KEY,
        sessionKey: String = SESSION_KEY,
        consentDefault: Boolean = false,
        sdk: FaroMeta.Sdk = FaroMeta.Sdk(TelemetryKitInfo.NAME, TelemetryKitInfo.VERSION),
    ) = Configuration(
        endpoint = "https://collector.example.com/collect",
        app = app,
        browser = browser,
        platform = platform,
        domain = domain,
        consentKey = consentKey,
        sessionKey = sessionKey,
        consentDefault = consentDefault,
        sdk = sdk,
        flushDelayMillis = flushDelayMillis,
        maxBatch = maxBatch,
        maxPending = maxPending,
        maxStoredBytes = maxStoredBytes,
        maxStoredAgeMillis = maxStoredAgeMillis,
        backoffMillis = backoffMillis,
        sessionInactivityMillis = sessionInactivityMillis,
        maxErrorValueLength = maxErrorValueLength,
        errorKind = errorKind,
        extraScrubParameters = extraScrubParameters,
        extraScrubPatterns = extraScrubPatterns,
    )

    fun event(name: String) = TelemetryItem.Event(FaroEvent(name, "android", "t", null))

    /** A client whose timed flushes never fire during a test (`flushDelayMillis` an hour): only explicit `flush()` sends */
    fun client(
        scope: CoroutineScope,
        transport: TelemetryTransport = RecordingTransport(),
        storeFile: File? = null,
        remote: TelemetryClient.RemoteGate = TelemetryClient.RemoteGate.UNKNOWN,
        clock: FakeClock = FakeClock(),
        store: InMemoryTelemetryStore = InMemoryTelemetryStore(),
        consent: Boolean = true,
        configuration: Configuration = configuration(),
    ): TelemetryClient {
        val client = TelemetryClient(configuration, transport, store, storeFile, consent, scope, clock.now)
        if (remote != TelemetryClient.RemoteGate.UNKNOWN) client.setRemote(remote)
        return client
    }
}
