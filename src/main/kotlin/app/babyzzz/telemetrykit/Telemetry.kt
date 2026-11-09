package app.babyzzz.telemetrykit

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * The facade a host app calls: errors, warning and error logs, events, measurements, sessions and the signed-in user.
 * Every call is a cheap, non-suspending enqueue, wrapped so a telemetry bug never reaches the caller, and a no-op until
 * [start] ran, in debug builds (unless explicitly enabled), when the user turned it off ([setConsent]) or while the
 * host's remote flag ([setRemoteEnabled]) is off. Only constants should name what happened; free values pass
 * [TelemetryScrub]; the user is an opaque id, never an e-mail or name. [client] is a replaceable global.
 */
public object Telemetry {
    @Volatile
    public var client: TelemetryClient? = null
        private set

    // region Set-up

    /** Whether this build may send at all: release builds, or a debug build with the host's debug switch on */
    public fun buildAllows(debug: Boolean, debugSwitch: Boolean): Boolean = !debug || debugSwitch

    /** The user's stored consent; [Configuration.consentDefault] when never set */
    public fun consent(store: TelemetryStore, configuration: Configuration): Boolean =
        store.getBoolean(configuration.consentKey) ?: configuration.consentDefault

    /**
     * At launch, before anything is recorded; restores (in the background) what the last run could not send. Returns
     * whether telemetry was started (false: this build may not send). [storeFile] is the pending file (null: nothing
     * is kept between launches).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    public fun start(
        configuration: Configuration,
        store: TelemetryStore,
        storeFile: File?,
        debug: Boolean,
        debugSwitch: Boolean,
        transport: TelemetryTransport = OkHttpTelemetryTransport(configuration.endpoint),
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1)),
    ): Boolean {
        if (!buildAllows(debug, debugSwitch)) return false
        val created = TelemetryClient(configuration, transport, store, storeFile, consent(store, configuration), scope)
        install(created)
        scope.launch { runCatching { created.restore() } }
        return true
    }

    /** Replaces the client (tests; null stops telemetry) */
    public fun install(client: TelemetryClient?) {
        this.client = client
    }

    // endregion

    // region Gates and identity

    /** The user's setting, stored under [Configuration.consentKey]; off drops everything queued at once */
    public fun setConsent(on: Boolean): Unit = safely { client?.setConsent(on) }

    /** The host's remote flag; null = no answer yet (items wait in memory), false drops, true sends */
    public fun setRemoteEnabled(enabled: Boolean?): Unit = safely {
        client?.setRemote(
            when (enabled) {
                null -> TelemetryClient.RemoteGate.UNKNOWN
                true -> TelemetryClient.RemoteGate.ENABLED
                false -> TelemetryClient.RemoteGate.DISABLED
            },
        )
    }

    /** After a sign-in or a restored session: an opaque user id; null after a sign-out */
    public fun setUser(subject: String?): Unit = safely { client?.setUser(subject) }

    /** The screen in view; a name from the host's own enumeration, never data */
    public fun setView(name: String?): Unit = safely { client?.setView(name) }

    // endregion

    // region Signals

    /** A named event: [name] and attribute values are constants or enumerated, low-cardinality strings */
    public fun event(name: String, attributes: Map<String, String> = emptyMap(), scrub: Boolean? = null): Unit = safely {
        val client = client ?: return@safely
        client.record(TelemetryItem.Event(FaroEvent(name, client.configuration.domain, timestamp(client), attributes.ifEmpty { null })), scrub)
    }

    /** At most once per [intervalMillis] per [key] (default: the name), with `suppressed`, how many were dropped since */
    public fun eventThrottled(
        name: String,
        attributes: Map<String, String> = emptyMap(),
        key: String = name,
        intervalMillis: Long = 60_000,
        scrub: Boolean? = null,
    ): Unit = safely {
        val client = client ?: return@safely
        client.recordThrottled(FaroEvent(name, client.configuration.domain, timestamp(client), attributes), key, intervalMillis, scrub)
    }

    /** A measurement (Faro `measurements`): [type] is a constant, [values] numbers */
    public fun measurement(type: String, values: Map<String, Double>, context: Map<String, String> = emptyMap(), scrub: Boolean? = null): Unit = safely {
        val client = client ?: return@safely
        client.record(TelemetryItem.Measurement(FaroMeasurement(type, values, timestamp(client), context.ifEmpty { null })), scrub)
    }

    /**
     * An error the host handled: its type name and [Configuration.errorValue] (no stack frames), with where it happened
     * ([where], a constant such as "checkout"). The message is scrubbed when the item is queued.
     */
    public fun error(error: Throwable, where: String, scrub: Boolean? = null): Unit = safely {
        val client = client ?: return@safely
        val type = error::class.java.simpleName.ifEmpty { "Throwable" }
        client.record(
            TelemetryItem.Exception(FaroException(type, client.configuration.errorValue(error), timestamp(client), mapOf("where" to where))),
            scrub,
        )
    }

    /** A warning or error line: a constant [message], a [category], and optional low-cardinality [context] */
    public fun log(level: FaroLog.Level, category: String, message: String, context: Map<String, String> = emptyMap(), scrub: Boolean? = null): Unit = safely {
        val client = client ?: return@safely
        client.record(TelemetryItem.Log(FaroLog(message, level, timestamp(client), logContext(context, category))), scrub)
    }

    // endregion

    // region Lifecycle

    /** The last activity stopped (ProcessLifecycleOwner `ON_STOP`): the queue is written to the pending file, then sent */
    public fun onStop(): Unit = safely { client?.onStop() }

    /** The app is back (ProcessLifecycleOwner `ON_START`): what was written is still queued in memory, so the file goes */
    public fun onStart(): Unit = safely { client?.foregrounded() }

    // endregion

    /** A caller's own `category` key never overrides the library's, which is exempt from scrubbing */
    internal fun logContext(context: Map<String, String>, category: String): Map<String, String> = context + ("category" to category)

    private fun timestamp(client: TelemetryClient) = FaroTimestamp.string(client.nowMillis)

    private inline fun safely(block: () -> Unit) {
        try {
            block()
        } catch (_: Exception) {
            // telemetry never throws into the caller
        }
    }
}
