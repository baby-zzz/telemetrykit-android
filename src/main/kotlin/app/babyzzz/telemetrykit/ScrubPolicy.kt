package app.babyzzz.telemetrykit

/**
 * Which free strings of an item [TelemetryScrub] runs over when the item is queued. Every field is on by default.
 * Names, types, levels, `where`, timestamps and meta are never free text and are never scrubbed. Turning a field off
 * sends its values as the host gave them: what then reaches the collector is the host app's responsibility. A per-call
 * `scrub` argument on the [Telemetry] functions overrides the policy for one item.
 */
public data class ScrubPolicy(
    /** Event attribute values */
    val eventAttributes: Boolean = true,
    /** Log messages */
    val logMessages: Boolean = true,
    /** Log context values (`category` excepted) */
    val logContext: Boolean = true,
    /** `exceptions[].value`, the error kind and message */
    val exceptionValues: Boolean = true,
    /** Exception context values (`where` excepted) */
    val exceptionContext: Boolean = true,
    /** Measurement context values */
    val measurementContext: Boolean = true,
) {
    public companion object {
        /** Everything scrubbed; the default */
        public val ALL: ScrubPolicy = ScrubPolicy()

        /** Nothing scrubbed */
        public val NONE: ScrubPolicy = ScrubPolicy(
            eventAttributes = false,
            logMessages = false,
            logContext = false,
            exceptionValues = false,
            exceptionContext = false,
            measurementContext = false,
        )
    }
}
