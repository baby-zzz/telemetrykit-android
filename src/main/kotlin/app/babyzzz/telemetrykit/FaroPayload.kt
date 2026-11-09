package app.babyzzz.telemetrykit

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The Faro receiver's JSON body (`POST <collector>/collect`, Alloy `faro.receiver`): the subset of signals the client
 * sends (events, logs, exceptions, measurements). Alloy turns `meta` into logfmt keys on every Loki line (`app_name`,
 * `app_namespace`, `session_id`, `session_attr_platform`, `user_id`, `browser_os`, `view_name`...), so the field names
 * are the Faro web SDK's. Every field has an explicit [SerialName]: the same types are the pending file, which must
 * survive refactors.
 */
@Serializable
public data class FaroPayload(
    val meta: FaroMeta,
    val events: List<FaroEvent>? = null,
    val logs: List<FaroLog>? = null,
    val exceptions: List<FaroException>? = null,
    val measurements: List<FaroMeasurement>? = null,
) {
    public companion object {
        /** Absent signal kinds are left out of the JSON (not empty arrays),  */
        public val json: Json = Json { explicitNulls = false; ignoreUnknownKeys = true }

        public fun of(meta: FaroMeta, items: List<TelemetryItem>): FaroPayload = FaroPayload(
            meta,
            events = items.filterIsInstance<TelemetryItem.Event>().map { it.event }.ifEmpty { null },
            logs = items.filterIsInstance<TelemetryItem.Log>().map { it.log }.ifEmpty { null },
            exceptions = items.filterIsInstance<TelemetryItem.Exception>().map { it.exception }.ifEmpty { null },
            measurements = items.filterIsInstance<TelemetryItem.Measurement>().map { it.measurement }.ifEmpty { null },
        )
    }
}

@Serializable
public data class FaroMeta(
    @SerialName("app") val app: App,
    @SerialName("sdk") val sdk: Sdk,
    @SerialName("session") val session: Session? = null,
    @SerialName("user") val user: User? = null,
    @SerialName("view") val view: View? = null,
    @SerialName("browser") val browser: Browser,
) {
    @Serializable
    public data class App(
        /** The app's name (`app_name`) */
        @SerialName("name") val name: String,
        /** `app_namespace`, e.g. the platform */
        @SerialName("namespace") val namespace: String,
        /** The app's version, e.g. `<versionName>+<versionCode>` */
        @SerialName("version") val version: String,
        /** `production` / `development`, a dashboard's `$env` */
        @SerialName("environment") val environment: String,
        @SerialName("bundleId") val bundleId: String,
    )

    @Serializable
    public data class Sdk(@SerialName("name") val name: String, @SerialName("version") val version: String)

    @Serializable
    public data class Session(
        @SerialName("id") val id: String,
        /** `platform: android` becomes `session_attr_platform=android` */
        @SerialName("attributes") val attributes: Map<String, String>,
    )

    /** An opaque user id only; never an e-mail or a name */
    @Serializable
    public data class User(@SerialName("id") val id: String)

    @Serializable
    public data class View(@SerialName("name") val name: String)

    @Serializable
    public data class Browser(
        /** The app's name as a browser name, e.g. `MyApp-Android` */
        @SerialName("name") val name: String,
        @SerialName("version") val version: String,
        /** e.g. `Android 16` */
        @SerialName("os") val os: String,
        @SerialName("mobile") val mobile: Boolean,
        @SerialName("language") val language: String,
    )
}

@Serializable
public data class FaroEvent(
    @SerialName("name") val name: String,
    @SerialName("domain") val domain: String,
    @SerialName("timestamp") val timestamp: String,
    @SerialName("attributes") val attributes: Map<String, String>? = null,
)

@Serializable
public data class FaroLog(
    @SerialName("message") val message: String,
    @SerialName("level") val level: Level,
    @SerialName("timestamp") val timestamp: String,
    @SerialName("context") val context: Map<String, String>? = null,
) {
    @Serializable
    public enum class Level {
        @SerialName("warn") WARN,
        @SerialName("error") ERROR,
    }
}

@Serializable
public data class FaroException(
    /** The stable type name, e.g. `ApiError` (kept through R8 by a `-keepnames` rule) */
    @SerialName("type") val type: String,
    /** [Configuration.errorValue]: the error's kind, then its message, scrubbed; no stack frames */
    @SerialName("value") val value: String,
    @SerialName("timestamp") val timestamp: String,
    @SerialName("context") val context: Map<String, String>? = null,
)

@Serializable
public data class FaroMeasurement(
    @SerialName("type") val type: String,
    @SerialName("values") val values: Map<String, Double>,
    @SerialName("timestamp") val timestamp: String,
    @SerialName("context") val context: Map<String, String>? = null,
)

/** One queued signal; the meta it is sent with is captured when it is recorded. */
@Serializable
public sealed class TelemetryItem {
    @Serializable
    @SerialName("event")
    public data class Event(@SerialName("event") val event: FaroEvent) : TelemetryItem()

    @Serializable
    @SerialName("log")
    public data class Log(@SerialName("log") val log: FaroLog) : TelemetryItem()

    @Serializable
    @SerialName("exception")
    public data class Exception(@SerialName("exception") val exception: FaroException) : TelemetryItem()

    @Serializable
    @SerialName("measurement")
    public data class Measurement(@SerialName("measurement") val measurement: FaroMeasurement) : TelemetryItem()

    /**
     * The same item with every free string scrubbed ([TelemetryScrub]). Names, types and log messages are the host's own
     * constants and are sent as they are.
     */
    public fun scrubbed(scrubber: TelemetryScrub = TelemetryScrub.default): TelemetryItem {
        fun scrub(text: String) = scrubber.text(text)
        fun scrubMap(map: Map<String, String>?) = map?.mapValues { scrub(it.value) }
        return when (this) {
            is Event -> Event(event.copy(attributes = scrubMap(event.attributes)))
            is Log -> Log(log.copy(context = scrubMap(log.context)))
            is Exception -> Exception(exception.copy(value = scrub(exception.value), context = scrubMap(exception.context)))
            is Measurement -> Measurement(measurement.copy(context = scrubMap(measurement.context)))
        }
    }
}

public object FaroTimestamp {
    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    /** RFC 3339 with milliseconds, UTC, as the Faro web SDK sends */
    public fun string(epochMillis: Long): String = formatter.format(Instant.ofEpochMilli(epochMillis))
}
