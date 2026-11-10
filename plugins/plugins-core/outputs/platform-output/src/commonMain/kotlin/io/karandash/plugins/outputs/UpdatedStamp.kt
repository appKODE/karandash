package io.karandash.plugins.outputs

import kotlin.time.Clock

/**
 * When a karandash comment was last posted or edited: `updated 2026-10-30 15:40 UTC` (label in the report language),
 * in the summary's `<sub>karandash …</sub>` footer and as the last line of an inline note. Added only to what is sent,
 * never to what change detection compares: [withoutStamp] takes it back out of a body read from the platform.
 */
internal class UpdatedStamp(
    private val clock: Clock,
    private val language: String?,
) {
    /** `updated YYYY-MM-DD HH:MM UTC`, minute precision. */
    fun text(): String {
        val iso = clock.now().toString() // ISO-8601 in UTC: 2026-10-30T15:40:12.345Z
        return "${InlineLifecycleText.UPDATED.of(language)} ${iso.take(MINUTE_LENGTH).replace('T', ' ')} UTC"
    }

    /** [body] (an inline note) ending with a fresh stamp line in place of any earlier one. */
    fun inline(body: String): String = "${withoutStamp(body)}\n\n<sub>${text()}</sub>"

    /** [body] (the summary) with the stamp appended to its last `<sub>karandash …</sub>` footer; unchanged without one. */
    fun summary(body: String): String {
        val lines = body.lines()
        val footer = lines.indexOfLast { it.startsWith(FOOTER_START) && it.endsWith(SUB_END) }
        if (footer < 0) return body
        return lines
            .mapIndexed { i, line -> if (i == footer) line.removeSuffix(SUB_END) + " · ${text()}$SUB_END" else line }
            .joinToString("\n")
    }

    companion object {
        private const val MINUTE_LENGTH = 16
        private const val FOOTER_START = "<sub>karandash "
        private const val SUB_END = "</sub>"

        /** A stamp line at the end of an inline note, in any language. */
        private val INLINE_STAMP = Regex("""\s*<sub>[^<\n]* \d{4}-\d{2}-\d{2} \d{2}:\d{2} UTC</sub>\s*$""")

        /** [body] without a trailing inline stamp line, trimmed at the end: what the bot's note says. */
        fun withoutStamp(body: String): String = body.replace(INLINE_STAMP, "").trimEnd()
    }
}
