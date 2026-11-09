package app.babyzzz.telemetrykit

/** The library's own name and version, the default for [Configuration.sdk] */
public object TelemetryKitInfo {
    public const val NAME: String = "telemetrykit-android"
    public const val VERSION: String = "0.2.0" // x-release-please-version
}

/**
 * Everything the client needs to know about the host app. There are no defaults for what identifies the app, the
 * collector or the store; the tuning values have the defaults documented on each field.
 */
public class Configuration(
    /** The Faro receiver URL, e.g. `https://collector.example.com/collect`; used by the default [OkHttpTelemetryTransport] */
    public val endpoint: String,
    /** `meta.app`: name, namespace, version, environment and bundle id of the host app */
    public val app: FaroMeta.App,
    /** `meta.browser`: name, version, OS, mobile flag and language (Faro's web-shaped device description) */
    public val browser: FaroMeta.Browser,
    /** `session_attr_platform`, a filter in dashboards: `android`, ... */
    public val platform: String,
    /** The `domain` of every event the client creates */
    public val domain: String,
    /** Store key of the user's consent (a Boolean; a missing value means [consentDefault]) */
    public val consentKey: String,
    /** Store key of the persisted session */
    public val sessionKey: String,
    /** Consent when the user never chose: false (opt-in) unless the host decides on opt-out */
    public val consentDefault: Boolean = false,
    public val sdk: FaroMeta.Sdk = FaroMeta.Sdk(TelemetryKitInfo.NAME, TelemetryKitInfo.VERSION),
    /** Wait before a timed flush after the first queued item */
    public val flushDelayMillis: Long = 5_000,
    /** Items per request, and the queue length that triggers an immediate flush */
    public val maxBatch: Int = 50,
    /** Items kept in memory; the oldest are dropped beyond it */
    public val maxPending: Int = 500,
    /** Size limit of the pending file; the oldest items are dropped to fit */
    public val maxStoredBytes: Int = 512 * 1024,
    /** Items in the pending file older than this are dropped when it is restored */
    public val maxStoredAgeMillis: Long = 24 * 60 * 60 * 1000L,
    /** Wait after the 1st, 2nd, ... consecutive failure; the last step repeats */
    public val backoffMillis: List<Long> = listOf(5_000, 30_000, 120_000, 600_000),
    /** A session ends after this long without a recorded item; there is no maximum length */
    public val sessionInactivityMillis: Long = 15 * 60 * 1000L,
    /** The longest `exceptions[].value` sent; longer values are cut */
    public val maxErrorValueLength: Int = 500,
    /** Query parameter names to scrub in addition to [TelemetryScrub.DEFAULT_SECRET_PARAMETERS] */
    public val extraScrubParameters: List<String> = emptyList(),
    /** Regexes whose matches are scrubbed in addition to the defaults */
    public val extraScrubPatterns: List<Regex> = emptyList(),
    /**
     * A stable kind for an error (a class name or status), put before its message in `exceptions[].value`. The default
     * is the class's simple name. Never include the message here: it is appended (scrubbed) separately.
     */
    public val errorKind: (Throwable) -> String = { it::class.java.simpleName.ifEmpty { "Throwable" } },
) {
    init {
        require(endpoint.isNotBlank()) { "endpoint is blank" }
        require(consentKey.isNotBlank() && sessionKey.isNotBlank()) { "store keys are blank" }
        require(flushDelayMillis > 0 && maxBatch > 0 && maxPending > 0 && maxStoredBytes > 0) { "limits must be positive" }
        require(maxStoredAgeMillis > 0 && sessionInactivityMillis > 0 && maxErrorValueLength > 0) { "limits must be positive" }
        require(backoffMillis.isNotEmpty() && backoffMillis.all { it >= 0 }) { "backoffMillis needs at least one non-negative step" }
    }

    /** The scrub every free string passes: the defaults plus [extraScrubParameters] and [extraScrubPatterns] */
    public val scrub: TelemetryScrub = TelemetryScrub(extraScrubParameters, extraScrubPatterns)

    /**
     * [errorKind], then the error's message (scrubbed when the item is queued), cut at [maxErrorValueLength]. No
     * stack frames.
     */
    public fun errorValue(error: Throwable): String {
        val kind = errorKind(error)
        val message = error.message?.trim().orEmpty()
        val value = if (message.isEmpty() || message == kind) kind else "$kind: $message"
        return value.take(maxErrorValueLength)
    }
}
