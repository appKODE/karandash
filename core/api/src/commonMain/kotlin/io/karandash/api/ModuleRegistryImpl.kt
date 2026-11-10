package io.karandash.api

import io.karandash.api.module.PlatformServiceModule
import io.karandash.api.module.RuleModule
import io.karandash.logger.Logger
import io.karandash.logger.NoOpLogger
import io.karandash.logger.debug
import io.karandash.normalize.ConfigKeyNormalizer
import io.karandash.normalize.ModuleIdNormalizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update

/**
 * Thread-safe implementation of ModuleRegistry.
 *
 * Uses [MutableStateFlow] with immutable snapshots for lock-free reads
 * and atomic [update] for writes. All read methods access [state.value]
 * without suspension.
 *
 * @param logger Receives a `warn` when a module takes over a rule id another module contributed, and a `debug`
 *   line when a module id is registered again.
 */
@InternalKarandashApi
public class ModuleRegistryImpl(
    private val logger: Logger = NoOpLogger,
) : ModuleRegistry {
    /**
     * Not @Serializable: runtime context holder, indexes live [Module] instances.
     *
     * [modules] maps an id to its parts, primary first; a plain module is a one-part entry. [indexed] keeps the
     * index keys each part had at registration, so [unregister] never calls back into a module the loader has
     * already shut down.
     */
    private data class RegistrySnapshot(
        val modules: Map<String, List<Module>> = emptyMap(),
        val indexed: Map<String, List<IndexedPart>> = emptyMap(),
        val ruleIndex: Map<String, RuleModule> = emptyMap(),
        val ruleCanonicalIds: Map<String, String> = emptyMap(),
        val aliasIndex: Map<String, PlatformServiceModule> = emptyMap(),
        val collisions: List<String> = emptyList(),
    )

    /** One part with its suspend-computed index keys, hoisted out of the non-suspend `state.update` lambda. */
    private class IndexedPart(
        val module: Module,
        val rules: Set<String>,
        val aliases: Set<String>,
    )

    private val state = MutableStateFlow(RegistrySnapshot())

    override suspend fun register(module: Module) {
        registerComposite(listOf(module))
    }

    override suspend fun registerComposite(modules: List<Module>) {
        require(modules.isNotEmpty()) { "registerComposite needs at least one module" }
        val id = modules.first().id
        require(modules.all { it.id == id }) { "Every part of composite '$id' must share its id" }
        val parts = modules.map { indexed(it) }
        logTakeovers(id, parts)
        state.update { s ->
            val added = s.copy(modules = s.modules + (id to modules), indexed = s.indexed + (id to parts))
            parts.fold(added) { acc, part -> acc.index(part) }
        }
    }

    override suspend fun unregister(id: String): Boolean {
        val before = state.getAndUpdate { s ->
            val removed = s.copy(modules = s.modules - id, indexed = s.indexed - id)
            s.indexed[id]?.fold(removed) { acc, part -> acc.unindex(part) } ?: s
        }
        return id in before.modules
    }

    private suspend fun indexed(module: Module): IndexedPart =
        IndexedPart(
            module = module,
            rules = if (module is RuleModule) module.contributedRules() else emptySet(),
            aliases = if (module is PlatformServiceModule) providerAliasKeys(module) else emptySet(),
        )

    private fun RegistrySnapshot.index(part: IndexedPart): RegistrySnapshot {
        val rule = part.module as? RuleModule
        val provider = part.module as? PlatformServiceModule
        val ruleKeys = part.rules.flatMap { ruleId -> ruleKeys(ruleId).map { it to ruleId } }
        val (taken, free) = part.aliases.partition { key -> aliasIndex[key].let { it != null && it.id != part.module.id } }
        return copy(
            ruleIndex = if (rule == null) ruleIndex else ruleIndex + ruleKeys.map { it.first to rule },
            ruleCanonicalIds = if (rule == null) ruleCanonicalIds else ruleCanonicalIds + ruleKeys,
            aliasIndex = if (provider == null) aliasIndex else aliasIndex + free.map { it to provider },
            collisions = collisions + taken.map { "Alias '$it' claimed by both '${aliasIndex.getValue(it).id}' and '${part.module.id}'" },
        )
    }

    private fun RegistrySnapshot.unindex(part: IndexedPart): RegistrySnapshot {
        val id = part.module.id
        val ruleKeys = part.rules.flatMap { ruleKeys(it) }.toSet()
        return copy(
            ruleIndex = ruleIndex - ruleKeys,
            ruleCanonicalIds = ruleCanonicalIds - ruleKeys,
            aliasIndex = aliasIndex - part.aliases.filter { aliasIndex[it]?.id == id }.toSet(),
            collisions = if (part.module is PlatformServiceModule) collisions.filter { "'$id'" !in it } else collisions,
        )
    }

    /** The raw, camelCase and normalized lookup keys of [ruleId]. */
    private fun ruleKeys(ruleId: String): List<String> =
        listOf(ruleId, kebabToCamelCase(ruleId), ModuleIdNormalizer.normalize(ruleId)).distinct()

    override fun get(id: String): Module? = state.value.modules[id]?.first()

    override fun getByType(type: ModuleType): List<Module> =
        state.value.modules.values
            .flatten()
            .filter { it.type == type }

    override fun registeredIds(): Set<String> = state.value.modules.keys

    override fun allModules(): List<Module> =
        state.value.modules.values
            .flatten()

    override fun hasRule(ruleId: String): Boolean {
        val snapshot = state.value.ruleIndex
        return snapshot.containsKey(ruleId) ||
            snapshot.containsKey(kebabToCamelCase(ruleId)) ||
            snapshot.containsKey(ModuleIdNormalizer.normalize(ruleId))
    }

    override fun findRuleHandler(ruleId: String): RuleModule? {
        val snapshot = state.value.ruleIndex
        return snapshot[ruleId]
            ?: snapshot[kebabToCamelCase(ruleId)]
            ?: snapshot[ModuleIdNormalizer.normalize(ruleId)]
    }

    override fun canonicalRuleId(ruleId: String): String {
        val snapshot = state.value.ruleCanonicalIds
        return snapshot[ruleId]
            ?: snapshot[kebabToCamelCase(ruleId)]
            ?: snapshot[ModuleIdNormalizer.normalize(ruleId)]
            ?: ruleId
    }

    override fun findByAlias(alias: String): PlatformServiceModule? = state.value.aliasIndex[Module.normalizeId(alias)]

    override fun allAliases(): Map<String, PlatformServiceModule> = state.value.aliasIndex

    override fun aliasCollisions(): List<String> = state.value.collisions

    private fun logTakeovers(
        id: String,
        parts: List<IndexedPart>,
    ) {
        val snapshot = state.value
        if (snapshot.modules[id] != null) {
            logger.debug { "Module '$id' registered again; the new instance replaces the old one" }
        }
        parts.flatMap { it.rules }.forEach { ruleId ->
            val previous = snapshot.ruleIndex[ruleId]
            if (previous != null && previous.id != id) {
                logger.warn("Rule '$ruleId' from module '${previous.id}' is now handled by module '$id'")
            }
        }
    }

    private suspend fun providerAliasKeys(provider: PlatformServiceModule): Set<String> =
        buildSet {
            add(Module.normalizeId(provider.id))
            add(Module.normalizeId(provider.dslName))
            for (alias in provider.aliases()) {
                add(Module.normalizeId(alias))
            }
        }

    private fun kebabToCamelCase(key: String): String = ConfigKeyNormalizer.kebabToCamelCase(key)
}
