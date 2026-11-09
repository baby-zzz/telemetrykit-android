package app.babyzzz.telemetrykit

/**
 * Keeps credentials and personal data out of what is sent: secret query values, e-mail addresses, bearer tokens and
 * credentials in `scheme://user:password@host`. Applied to every free string of an item before it is queued. Add your
 * own query parameter names ([extraParameters]) and regexes ([extraPatterns], the whole match is replaced) on top of
 * the defaults; the defaults cannot be turned off. A server-side scrub (Alloy `loki.process`) is still advisable.
 *
 * Fails closed: if a pattern cannot be built or throws, nothing of the text is sent.
 */
public class TelemetryScrub internal constructor(
    extraParameters: List<String>,
    extraPatterns: List<Regex>,
    available: Boolean,
) {
    public constructor(
        extraParameters: List<String> = emptyList(),
        extraPatterns: List<Regex> = emptyList(),
    ) : this(extraParameters, extraPatterns, available = true)

    internal class Patterns(extraParameters: List<String>, val extra: List<Regex>) {
        // ?name=value or &name=value up to the next &, #, quote or whitespace
        val parameter = Regex(
            "([?&](?:${(DEFAULT_SECRET_PARAMETERS + extraParameters).joinToString("|") { Regex.escape(it) }})=)[^&#\\s\"']*",
            RegexOption.IGNORE_CASE,
        )

        // letters-only top-level domain, so package versions ("sdk@1.2.3") stay
        val email = Regex("[\\w.%+-]+@[a-z0-9.-]+\\.[a-z]{2,}\\b", RegexOption.IGNORE_CASE)
        val bearer = Regex("(Bearer\\s+)[A-Za-z0-9._~+/=-]+")
        val userInfo = Regex("([a-z][a-z0-9+.-]*://)[^/?#@\\s]+@", RegexOption.IGNORE_CASE)
    }

    private val patterns: Patterns? = if (available) runCatching { Patterns(extraParameters, extraPatterns) }.getOrNull() else null

    /** The text with credentials in URLs, secret query values, bearer tokens and e-mail addresses replaced */
    public fun text(value: String): String = text(value, patterns)

    internal fun text(value: String, patterns: Patterns?): String {
        if (patterns == null) return REDACTED
        return runCatching {
            var out = value.replace(patterns.userInfo) { "${it.groupValues[1]}$REDACTED@" }
            if (out.contains('=')) out = out.replace(patterns.parameter) { "${it.groupValues[1]}$REDACTED" }
            out = out.replace(patterns.bearer) { "${it.groupValues[1]}$REDACTED" }
            if (out.contains('@')) out = out.replace(patterns.email, EMAIL_REDACTED)
            for (regex in patterns.extra) out = out.replace(regex, REDACTED)
            out
        }.getOrDefault(REDACTED)
    }

    public companion object {
        public const val REDACTED: String = "[REDACTED]"
        public const val EMAIL_REDACTED: String = "[email]"

        /** Query parameters whose values are secrets, always scrubbed */
        public val DEFAULT_SECRET_PARAMETERS: List<String> = listOf(
            "signed", "ts", "token", "access_token", "refresh_token", "id_token", "code", "state", "session_state", "key",
            "password", "secret",
        )

        /** Only the defaults */
        public val default: TelemetryScrub = TelemetryScrub()

        /** A scrub whose patterns could not be built: every string becomes [REDACTED] (tests of the fail-closed path) */
        internal val unavailable: TelemetryScrub = TelemetryScrub(emptyList(), emptyList(), available = false)
    }
}
