package io.karandash.api

import io.karandash.api.cache.CacheProvider
import io.karandash.api.context.EnrichmentContext
import io.karandash.api.platform.SubprocessBudget
import io.karandash.logger.Logger
import io.karandash.logger.NoOpLogger
import io.karandash.sdk.content.ContentSource
import kotlinx.serialization.json.JsonElement

/** Provides the plugin's deserialized configuration. */
public interface PluginConfigProvider {
    public val pluginConfig: JsonElement
}

/** Provides a logger for plugin output. */
public interface PluginLogger {
    public val logger: Logger get() = NoOpLogger
}

/** Provides access to environment variables (allowlist-filtered). */
public interface PluginEnvironment {
    public fun env(name: String): String?
}

/** Provides host runtime information. */
public interface PluginRuntime {
    public val hostVersion: String
    public val workingDirectory: String

    /**
     * Directory containing the config file. [ContentSource.LocalFile] paths in config
     * (e.g. scope instructions, extra-instructions) resolve relative to this.
     * Defaults to [workingDirectory].
     */
    public val configDir: String get() = workingDirectory
}

/** Provides security-related configuration. */
public interface PluginSecurity {
    /**
     * When true, SSRF protection is relaxed: localhost, private IPs, and HTTP
     * URLs are permitted for providers and webhooks. Intended for development
     * with self-hosted services (local GitLab, Gerrit, Gitea, etc.).
     */
    public val allowLocalhost: Boolean get() = false
}

/** Provides access to the cache subsystem. */
public interface PluginCacheAccess {
    /**
     * Cache subsystem for creating and accessing named caches.
     *
     * Modules can request string-keyed caches via [CacheProvider.getStringCache].
     * Default: [CacheProvider.NOOP] (no caching, safe to call without null checks).
     */
    public val cacheProvider: CacheProvider get() = CacheProvider.NOOP
}

/** Provides access to the per-run subprocess budget used by AI context providers. */
public interface PluginSubprocessAccess {
    /**
     * Shared budget tracking external subprocess invocations (git CLI, primarily).
     *
     * Default: [SubprocessBudget.UNLIMITED] - preserves existing behaviour for hosts
     * and plugins that have not yet been updated. A per-run validation pipeline
     * constructs a bounded [SubprocessBudget] and exposes it via its own
     * [PluginContext] implementation.
     */
    public val subprocessBudget: SubprocessBudget get() = SubprocessBudget.UNLIMITED
}

/**
 * Full plugin context combining all capability interfaces.
 *
 * Existing implementations are unchanged - this interface now composes
 * the focused interfaces above. Plugins that need only a subset can
 * accept the narrower interface type.
 */
@StableKarandashApi
public interface PluginContext :
    PluginConfigProvider,
    PluginLogger,
    PluginEnvironment,
    PluginRuntime,
    PluginSecurity,
    PluginCacheAccess,
    PluginSubprocessAccess {
    /** Convenience shortcut - delegates to [logger].[info][Logger.info]. */
    public fun log(message: String) {
        logger.info(message)
    }

    /** Convenience shortcut - delegates to [logger].[warn][Logger.warn]. */
    public fun warn(message: String) {
        logger.warn(message)
    }

    /**
     * Enrichment data available for this validation run.
     *
     * Contains data from all enrichment providers that ran successfully.
     * For rule modules, the context is filtered to only include enrichment IDs
     * declared in [RuleModule.supportedEnrichment].
     *
     * Default: [EnrichmentContext.EMPTY] (no enrichment data available).
     */
    public val enrichment: EnrichmentContext get() = EnrichmentContext.EMPTY

    /**
     * Resolve a pattern string (named, glob, pomsky, or regex) to a compiled [Regex].
     *
     * Supports all 5 pattern tiers:
     * - `@name` - named pattern from the registry
     * - `glob:pattern` - text glob pattern
     * - `pomsky:expression` - Pomsky DSL expression
     * - `regex:pattern` - explicit regex
     * - bare string - raw regex
     *
     * Plugin authors can call this to avoid duplicating pattern compilation logic.
     * Example: `context.resolvePattern("@jira-ticket(@project:MAPS)")`
     *
     * The host context compiles every tier. This default (used by contexts the host did not
     * build, e.g. test doubles) never throws: it returns a pattern matching [pattern] literally,
     * so no untrusted expression is compiled outside the host's guarded resolver.
     */
    public fun resolvePattern(pattern: String): Regex = Regex(Regex.escape(pattern))

    /**
     * The `@name` form of every named pattern the host's registry (core, module and user `patterns:`) compiles
     * without parameters. The plugin loader resolves each one into a bridged guest's context, so a JAR, native or
     * WASM guest resolves a named pattern its own config never mentions.
     *
     * Default: none (a context the host did not build has no registry).
     */
    public fun namedPatternNames(): List<String> = emptyList()

    /**
     * Resolve a [ContentSource] to its string content.
     *
     * Default: [ContentSource.Inline] returns its value; every other source needs the host
     * and throws here.
     */
    @ExperimentalKarandashApi
    public suspend fun resolveContent(source: ContentSource): String =
        when (source) {
            is ContentSource.Inline -> source.value
            else -> error("Content resolution not available in this context")
        }
}
