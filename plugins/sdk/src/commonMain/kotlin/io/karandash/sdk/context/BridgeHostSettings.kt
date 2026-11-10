package io.karandash.sdk.context

import io.karandash.sdk.regex.SafeRegex
import io.karandash.utils.mr.ShortSha
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * Host display settings carried in the bridge context JSON (the object a bridged guest receives in
 * `initialize`), so an out-of-process guest formats like the host does.
 *
 * Kotlin guests call [apply] with the parsed context; guests in other languages read [SHORT_SHA_LENGTH] directly.
 */
public object BridgeHostSettings {
    /** Context key: the host's `display.git.shortShaLength`, already clamped. */
    public const val SHORT_SHA_LENGTH: String = "shortShaLength"

    /** Context key: the `{name: {source, options}}` map of named patterns the host resolved for the guest. */
    public const val PATTERNS: String = "patterns"

    /** Key of a [PATTERNS] entry: the compiled regex source. */
    public const val PATTERN_SOURCE: String = "source"

    /** Key of a [PATTERNS] entry: the [RegexOption] names. */
    public const val PATTERN_OPTIONS: String = "options"

    /** Configures this guest process from [context]; absent or invalid values keep the SDK defaults. */
    public fun apply(context: JsonObject) {
        (context[SHORT_SHA_LENGTH] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }?.let(ShortSha::configure)
    }

    /**
     * The host-resolved regex for the named pattern [name] in [context], recompiled through [SafeRegex] with its
     * options as inline flags; null when the host sent no such pattern or it does not compile here.
     */
    public fun pattern(
        context: JsonObject,
        name: String,
    ): Regex? {
        val entry = (context[PATTERNS] as? JsonObject)?.get(name) as? JsonObject ?: return null
        val source = (entry[PATTERN_SOURCE] as? JsonPrimitive)?.content.orEmpty()
        val options = (entry[PATTERN_OPTIONS] as? JsonArray).orEmpty().map { (it as? JsonPrimitive)?.content }
        val flags = options.mapNotNull { INLINE_FLAGS[it] }.joinToString("")
        val body = if ("LITERAL" in options) Regex.escape(source) else source
        return SafeRegex.compileOrNull(if (flags.isEmpty()) body else "(?$flags)$body")
    }

    // JVM RegexOption names as the host sends them; common RegexOption has only IGNORE_CASE and MULTILINE
    private val INLINE_FLAGS =
        mapOf(
            RegexOption.IGNORE_CASE.name to "i",
            RegexOption.MULTILINE.name to "m",
            "DOT_MATCHES_ALL" to "s",
            "COMMENTS" to "x",
        )
}
