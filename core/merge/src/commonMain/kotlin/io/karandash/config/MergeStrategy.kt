package io.karandash.config

/**
 * Top-level merge strategy for config inheritance.
 *
 * - [DEEP] recursively merges maps, preserving parent keys not present in child.
 * - [SHALLOW] merges only top-level keys; nested maps are replaced entirely.
 * - [REPLACE] child config completely replaces parent config with no merging.
 */
enum class MergeStrategy(
    val value: String,
) {
    DEEP("deep"),
    SHALLOW("shallow"),
    REPLACE("replace"),
    ;

    companion object {
        fun fromString(value: String): MergeStrategy =
            entries.firstOrNull { it.value.equals(value, ignoreCase = true) }
                ?: throw IllegalArgumentException("Unknown MergeStrategy: '$value'")
    }
}
