package io.karandash.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Script execution mode for entries in the `scripts:` config block.
 *
 * - [AUTO] detect from file extension: `.mjs` -> subprocess, everything else -> sandbox.
 * - [SANDBOX] embedded QuickJS engine, no filesystem/network access.
 * - [SUBPROCESS] Node.js subprocess with full system access.
 */
@Serializable
enum class ScriptMode(
    val value: String
) {
    @SerialName("sandbox")
    SANDBOX("sandbox"),

    @SerialName("subprocess")
    SUBPROCESS("subprocess"),

    @SerialName("auto")
    AUTO("auto"),
    ;

    companion object {
        fun fromString(value: String): ScriptMode =
            entries.firstOrNull { it.value.equals(value, ignoreCase = true) }
                ?: throw IllegalArgumentException("Unknown ScriptMode: '$value'")
    }
}
