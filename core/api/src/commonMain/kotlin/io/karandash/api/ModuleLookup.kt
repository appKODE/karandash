package io.karandash.api

import io.karandash.api.module.PlatformServiceModule
import io.karandash.api.module.RuleModule

/**
 * Read-only view of loaded modules.
 *
 * [ModuleRegistry] extends this with registration. Per-run views returned by
 * [ModuleRegistry.forRun] expose only this interface, so a run cannot register or
 * unregister modules.
 */
@ExperimentalKarandashApi
public interface ModuleLookup {
    public fun get(id: String): Module?

    public fun getByType(type: ModuleType): List<Module>

    public fun registeredIds(): Set<String>

    /**
     * Every registered module, including each part of a composite registration ([ModuleRegistry.registerComposite]),
     * where [get] returns only the first part of an id. Use this, not `registeredIds().mapNotNull { get(it) }`, to
     * see every module type a plugin serves.
     */
    public fun allModules(): List<Module> = registeredIds().mapNotNull(::get)

    public fun hasRule(ruleId: String): Boolean

    public fun findRuleHandler(ruleId: String): RuleModule?

    /** Returns the canonical (original) rule ID for a given lookup key. */
    public fun canonicalRuleId(ruleId: String): String = ruleId

    public fun findByAlias(alias: String): PlatformServiceModule?

    public fun allAliases(): Map<String, PlatformServiceModule>

    public fun aliasCollisions(): List<String>
}
