/**
 * Dependency direction rules (docs/development/conventions.md "Dependency Direction"), checked by `karandash.kmp-library` on production
 * configurations only (test configurations are exempt). Known edges awaiting removal are allowlisted.
 *
 * - `:core:*` -> no cli, dsl or plugin tiers (`:core:*` -> `:plugins:sdk` is allowed, never the reverse)
 * - `:cli` -> no plugins-ai (AI-tier calls go through `:cli-ai`)
 * - plugins/sdk -> only core/api and the SDK-public core leaves; no cli, dsl or tiers
 * - plugin tiers -> no cli, no core/config (enrichment keys and parser live in the SDK, the glob matcher in core/glob)
 */
object DependencyDirection {
    private val tierPrefixes = listOf(":plugins:plugins-core:", ":plugins:plugins-extended:", ":plugins:plugins-ai:")
    private val cliModules = listOf(":cli", ":cli-ai", ":cli-app")
    private val upperLayers = cliModules + ":dsl:" + tierPrefixes
    private val sdkCoreLeaves =
        setOf(
            ":core:api",
            ":core:logger",
            ":core:models",
            ":core:conditions",
            ":core:multipattern",
            ":core:common",
            ":core:regex-safety", // implementation only, behind io.karandash.sdk.regex.SafeRegex
        )
    private val allowlist = emptyMap<String, Set<String>>()

    /** Why [dep] is a forbidden production dependency of [from], or null when the edge is allowed. */
    fun forbiddenEdge(
        from: String,
        dep: String,
    ): String? =
        when {
            dep in allowlist[from].orEmpty() -> null
            from.startsWith(":core:") && matchesAny(dep, upperLayers) -> "core -> cli/dsl/plugin tiers is forbidden"
            from == ":cli" && dep.startsWith(":plugins:plugins-ai:") ->
                "cli -> plugins-ai is forbidden; call the AI tier through :cli-ai"
            from == ":plugins:sdk" && (matchesAny(dep, upperLayers) || (dep.startsWith(":core:") && dep !in sdkCoreLeaves)) ->
                "plugins/sdk may depend only on core/api and the SDK-public core leaves $sdkCoreLeaves"
            matchesAny(from, tierPrefixes) && matchesAny(dep, cliModules) -> "plugin tier -> cli is forbidden"
            matchesAny(from, tierPrefixes) && dep == ":core:config" ->
                "plugin tier -> core/config is forbidden; enrichment keys/parser are in the SDK (io.karandash.sdk.enrichment)"
            else -> null
        }

    private fun matchesAny(
        path: String,
        prefixes: List<String>,
    ): Boolean = prefixes.any { path == it || (it.endsWith(":") && path.startsWith(it)) }
}
