package io.karandash.manifest

/**
 * Controls how [MissingModuleDetector] reacts to config keys that reference
 * unloaded modules.
 */
enum class MissingPluginBehavior {
    /**
     * Emit a [io.karandash.api.errors.MissingPluginException] (E0302/E0303). Validation does not proceed.
     * Recommended for CI environments where unknown rules must be caught.
     */
    FAIL,

    /**
     * Print a WARNING for each unloaded module reference, then continue.
     * Unknown config sections are stripped from the merged map before schema coercion.
     * This is the **default**.
     */
    WARN,

    /**
     * Silently strip unknown sections and continue.
     * Not recommended; use only for legacy migration.
     */
    IGNORE;

    companion object {
        /**
         * Parse a string value (case-insensitive) to [MissingPluginBehavior].
         * Returns null for unrecognised values.
         */
        fun fromString(value: String): MissingPluginBehavior? = entries.firstOrNull { it.name.equals(value.trim(), ignoreCase = true) }
    }
}
