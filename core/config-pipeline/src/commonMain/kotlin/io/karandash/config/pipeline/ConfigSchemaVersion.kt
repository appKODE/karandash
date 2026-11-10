package io.karandash.config.pipeline

import io.karandash.Version
import io.karandash.api.exception.ConfigParseException
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The top-level `version` key declares the config schema version (`major.minor`). A config that declares a newer
 * version than [CURRENT] needs a newer karandash and fails the load; an equal or lower one is accepted silently.
 */
object ConfigSchemaVersion {
    /** The config schema version this karandash supports; `init` writes it into the generated config. */
    const val CURRENT = "1.0"

    private const val KEY = "version"
    private val FORMAT = Regex("""(\d+)(?:\.(\d+))?""")

    /** Throws [ConfigParseException] when the `version` of the normalized [root] is not numeric or newer than [CURRENT]. */
    fun check(root: JsonObject) {
        val declared = (root[KEY] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content ?: return
        val parsed = parse(declared)
            ?: throw ConfigParseException("Config 'version: $declared' is not a number", hint = "Use '$KEY: $CURRENT' (major.minor).")
        if (parsed > checkNotNull(parse(CURRENT)) { "CURRENT is numeric" }) {
            throw ConfigParseException(
                "Config 'version: $declared' needs a newer karandash: this ${Version.NAME} ${Version.VERSION} supports config version up to $CURRENT",
                hint = "Upgrade karandash, or lower '$KEY' if the config does not use newer features.",
            )
        }
    }

    private fun parse(text: String): Pair<Int, Int>? =
        FORMAT.matchEntire(text.trim())?.let { m ->
            m.groupValues[1].toIntOrNull()?.let { it to (m.groupValues[2].toIntOrNull() ?: 0) }
        }

    private operator fun Pair<Int, Int>.compareTo(other: Pair<Int, Int>): Int =
        if (first != other.first) first.compareTo(other.first) else second.compareTo(other.second)
}
