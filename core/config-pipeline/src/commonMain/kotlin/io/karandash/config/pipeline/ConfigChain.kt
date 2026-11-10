package io.karandash.config.pipeline

import io.karandash.config.MergeStrategy
import io.karandash.config.merge.MergeStrategyMap
import io.karandash.manifest.ConfigSource
import io.karandash.manifest.RemoteFileResolution
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * An ordered list of config entries resolved via BFS from the root config's `extends:` chain.
 *
 * Entries are ordered deepest-ancestor-first, root-last.
 * During merge, entry[0] is the base and entry[last] (root) wins on conflict.
 *
 * Not @Serializable: runtime context holder, holds a non-serializable `ChainEntry`.
 */
data class ConfigChain(
    val entries: List<ChainEntry>
) {
    init {
        require(entries.isNotEmpty()) { "ConfigChain must have at least one entry" }
    }

    /** The root (child) config entry - always the last entry. */
    val root: ChainEntry get() = entries.last()

    /** Total number of entries in the chain (1 = root only, no extends). */
    val size: Int get() = entries.size

    /** The [ChainFetchKeys] of the whole chain, merged deepest ancestor first (the root wins). */
    val fetchKeys: JsonObject
        get() = entries.fold(JsonObject(emptyMap())) { merged, entry -> ChainFetchKeys.merge(merged, entry.fetchKeys) }
}

/**
 * A single node in a [ConfigChain].
 *
 * @property source Source location of the config file.
 * @property depth Depth in the extends tree (0 = deepest ancestor, increasing toward root).
 * @property mergeStrategy Per-entry merge strategy declared by THIS entry for its own parents.
 *   This is NOT a shared strategy across all siblings.
 * @property content Raw file content.
 * @property format Format id (e.g. "yaml", "json", "kts").
 * @property fileResolution How `{ file: "..." }` references in remote configs are resolved on failure.
 * @property fetchKeys This entry's own [ChainFetchKeys] (not inherited ones).
 * @property parsed [content] already parsed by its format module while the chain was resolved, so
 *   [ConfigChainParser] does not parse it again; null when the entry was built without it.
 * @property topLevelMerge How this entry merges as a whole (string `merge-strategy`).
 *
 * Not @Serializable: runtime context holder, holds a non-serializable `ConfigSource`.
 */
data class ChainEntry(
    val source: ConfigSource,
    val depth: Int,
    val mergeStrategy: MergeStrategyMap?,
    val content: String,
    val format: String,
    val fileResolution: RemoteFileResolution = RemoteFileResolution.LOCAL,
    val fetchKeys: JsonObject = JsonObject(emptyMap()),
    val parsed: JsonElement? = null,
    val topLevelMerge: MergeStrategy = MergeStrategy.DEEP,
)
