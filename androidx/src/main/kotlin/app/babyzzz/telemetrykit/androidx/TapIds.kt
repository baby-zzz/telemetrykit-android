package app.babyzzz.telemetrykit.androidx

import app.babyzzz.telemetrykit.TelemetryScrub

/**
 * Decides what is sent as a tap's `element_id`, from the element's test tag (never from its text).
 *
 * - [Automatic] (the default): no list to maintain. A tag is sent when it looks like a constant id: letters, digits
 *   and `_ . : -`, starting with a letter, at most 64 characters, no run of four or more digits, no `@`, and unchanged by
 *   the core's [TelemetryScrub]. Anything else is sent as [MASKED].
 * - The strict form, `TapIds(literals, templates)`: a tag in [literals] is sent as it is; a tag matching a template
 *   (`monitor-tile-*`, where `*` matches any text) is sent as the template itself; every other tag is [MASKED].
 *   Recommended when your tags can embed user data (a name, an e-mail or an id in an interpolated tag).
 *
 * A tappable without a tag is sent with an empty `element_id`, so gaps show up in the data. Not for names or values.
 */
public class TapIds private constructor(
    private val literals: Set<String>?,
    private val templates: List<Template>,
    @Suppress("UNUSED_PARAMETER") internal: Unit,
) {
    /** The strict form: only [literals] and [templates] are ever sent (see the class documentation) */
    public constructor(literals: Set<String>, templates: List<String> = emptyList()) : this(literals.toSet(), templates.map { Template(it) }, Unit)

    internal class Template(val text: String) {
        val regex: Regex = Regex(text.split('*').joinToString(".*") { Regex.escape(it) })

        fun matches(tag: String): Boolean = regex.matches(tag)
    }

    /** What to send as `element_id` for [tag] (null: the element has no tag): the tag, a template, [MASKED], or "" */
    public fun resolve(tag: String?): String {
        if (tag.isNullOrEmpty()) return ""
        if (literals == null) return if (looksConstant(tag)) tag else MASKED
        if (tag in literals) return tag
        return templates.firstOrNull { it.matches(tag) }?.text ?: MASKED
    }

    public companion object {
        /** Sent for a tag that is not allowed */
        public const val MASKED: String = "[masked]"

        private val CONSTANT = Regex("^[A-Za-z][A-Za-z0-9_.:-]{0,63}$")
        private val DIGIT_RUN = Regex("\\d{4,}")

        /** Tags that look like constant ids are sent; anything else is masked (see the class documentation) */
        @JvmField
        public val Automatic: TapIds = TapIds(null, emptyList(), Unit)

        internal fun looksConstant(tag: String): Boolean =
            CONSTANT.matches(tag) && !DIGIT_RUN.containsMatchIn(tag) && '@' !in tag && TelemetryScrub.default.text(tag) == tag
    }
}
