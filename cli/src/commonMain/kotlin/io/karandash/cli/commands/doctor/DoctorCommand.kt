package io.karandash.cli.commands.doctor

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import io.karandash.api.ModuleRegistry
import io.karandash.api.ai.AiMetricModule
import io.karandash.api.ai.AiReportModule
import io.karandash.api.errors.ConfigException
import io.karandash.api.errors.MissingPluginException
import io.karandash.api.exception.ConfigParseException
import io.karandash.api.module.EnrichmentProviderModule
import io.karandash.api.module.PlatformServiceModule
import io.karandash.api.support.rethrowCancellation
import io.karandash.api.support.suspendCatching
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cli.SuspendCliktCommand
import io.karandash.cli.commands.validate.FormatOutputPlanner
import io.karandash.cli.commands.validate.ReporterModuleFormat
import io.karandash.cli.errors.ExitCodes
import io.karandash.cli.pipeline.ConfigLoader
import io.karandash.cli.pipeline.hostContentResolver
import io.karandash.cli.pipeline.mergedAuthBlocks
import io.karandash.cli.plugin.loadPluginsWithManifest
import io.karandash.conditions.BuiltinPredicates
import io.karandash.config.Config
import io.karandash.config.LimitsConfig
import io.karandash.config.PolicyConfig
import io.karandash.config.pipeline.AuthBlockParser
import io.karandash.config.pipeline.ConfigDiscovery
import io.karandash.config.pipeline.PolicyConfigParser
import io.karandash.content.AuthRegistry
import io.karandash.detect.CIDetector
import io.karandash.detect.PlatformDetector
import io.karandash.detect.ProviderAutoDetector
import io.karandash.detect.SmartReporterDefaults
import io.karandash.http.NetworkPolicy
import io.karandash.platform.safeParent
import io.karandash.sdk.NoOpLogger
import kotlinx.io.files.Path
import kotlinx.serialization.json.JsonObject

/**
 * Command to check configuration health and authentication in native mode.
 *
 * Runs health checks:
 * 1. Config validation - discovers and parses config; then its `pomsky:` patterns (compile error fails, lint warns)
 * 2. Authentication - checks provider module loading status
 * 3. Plugins - loads external plugins and reports status
 * 4. Platform detection - tests auto-detection
 * 5. CI detection - displays CI environment info
 *
 * Exit codes:
 * - 0: All checks passed
 * - 1: Some checks failed (non-fatal)
 * - 2: Config parse error (fatal)
 *
 * @param sharedBootstrap Optional pre-loaded bootstrap to reuse. When non-null, plugin loading
 *   is skipped and the provided bootstrap/registry is used directly. The caller owns shutdown.
 */
class DoctorCommand(
    private val sharedBootstrap: PluginBootstrap,
    private val workingDir: Path? = null,
) : SuspendCliktCommand(
        name = "doctor",
    ) {
    private companion object {
        private const val MODULE_ID_COLUMN_WIDTH = 42
    }

    override fun help(context: Context) = "Check configuration, authentication, and plugin health"

    private val showPluginDetails by option(
        "--plugins",
        help = "Show detailed plugin status table (built-in and community modules)"
    ).flag(default = false)

    private val offline by option(
        "--offline",
        help = "Skip the credential check against each platform API (report configured platforms only)"
    ).flag(default = false)

    private fun reportPluginDetails(registry: ModuleRegistry) {
        echo("[Plugin Details]")
        val allIds = registry.registeredIds()
        if (allIds.isEmpty()) {
            echo("  No modules registered")
        } else {
            echo("  Module ID                                  Status")
            echo("  ─────────────────────────────────────────  ──────")
            for (moduleId in allIds.sorted()) {
                val status = if (registry.get(moduleId) != null) "OK" else "NOT_LOADED"
                echo("  ${moduleId.padEnd(MODULE_ID_COLUMN_WIDTH)} $status")
            }
        }
        echo("")
    }

    override suspend fun suspendRun() {
        echo("Karandash Doctor")
        echo("================")
        echo("")

        var hasFailures = false

        echo("[Config]")
        val configResult = checkConfig()
        when (configResult) {
            is DoctorCheckResult.Fatal -> {
                echoFailure(configResult.message, configResult.hint)
                echo("")
                echo("Result: Config parse error (fatal)")
                throw ProgramResult(ExitCodes.CONFIG_ERROR)
            }

            is DoctorCheckResult.Fail -> {
                echoFailure(configResult.message, configResult.hint)
                hasFailures = true
            }

            is DoctorCheckResult.Pass, is DoctorCheckResult.Warn -> {
                reportResult(configResult) {}
            }
        }
        echo("")

        echo("[Patterns]")
        DoctorCommandCommon.checkPatterns(configResult).forEach { reportResult(it) { hasFailures = true } }
        echo("")

        if (configResult is DoctorCheckResult.Pass) {
            DoctorRolloutChecks(sharedBootstrap.logger, offline).run(configResult).forEach { (title, results) ->
                echo("[$title]")
                results.forEach { reportResult(it) { hasFailures = true } }
                echo("")
            }
        }

        val registry = sharedBootstrap.registry()

        echo("[Auth]")
        val authResults = checkAuth(configResult, offline)
        for (result in authResults) {
            reportResult(result) { hasFailures = true }
        }
        if (authResults.isEmpty()) {
            echo("  SKIP: No auth providers configured")
        }
        echo("")

        echo("[Plugins]")
        val pluginResults = checkPlugins(sharedBootstrap)
        for (result in pluginResults) {
            reportResult(result) { hasFailures = true }
        }
        echo("")

        if (showPluginDetails) reportPluginDetails(registry)

        val providers = registry.platformServices()
        reportPlatformDetection(providers)
        echo("")

        echo("[Provider Detection Scores]")
        val autoDetector = ProviderAutoDetector(providers)
        val scores = autoDetector.detectAll()
        if (scores.isNotEmpty()) {
            for (score in scores) {
                echo("  ${score.providerId}: ${score.score} (${score.source})")
            }
            val best = autoDetector.detectBest()
            if (best != null) {
                echo("  Selected: ${best.providerId} (score=${best.score})")
            } else {
                echo("  No provider meets minimum threshold")
            }
        } else {
            echo("  No providers detected in current environment")
        }
        echo("")

        echo("[Reporter Defaults]")
        val bestDetection = autoDetector.detectBest()
        val reporterDefaults = SmartReporterDefaults(registry)
        val diag = reporterDefaults.diagnostics(bestDetection)
        echo("  Mode: ${if (diag.isCI) "CI" else "interactive"}")
        echo("  Selected format: ${diag.selectedFormat ?: "none"}")
        echo("  CI default: ${diag.ciDefault ?: "none"}")
        echo("  Interactive default: ${diag.interactiveDefault ?: "none"}")
        echo("")

        echo("[Report Formats]")
        reportFormatsAndOutputs(registry, (configResult as? DoctorCheckResult.Pass)?.config ?: Config(), bestDetection != null)
        echo("")

        echo("[Policy Validation]")
        val policyResults = checkPolicies(configResult)
        if (policyResults.isEmpty()) {
            echo("  SKIP: No policies configured")
        } else {
            for (result in policyResults) {
                reportResult(result) { hasFailures = true }
            }
        }
        echo("")

        echo("[Enrichment Providers]")
        val enrichmentProviders = registry.enrichmentProviders()
        reportEnrichmentProviders(enrichmentProviders)
        echo("")

        echo("[AI Reports]")
        reportAiReports(registry.aiReports())
        echo("")

        echo("[AI Metrics]")
        reportAiMetrics(registry.aiMetrics())
        echo("")

        if (hasFailures) {
            echo("Result: Some checks failed")
            throw ProgramResult(ExitCodes.VALIDATION_FAILED)
        } else {
            echo("Result: All checks passed")
        }
    }

    /** Format and output modules plus runnable reporter modules, and the pairs a run with this config selects. */
    private fun reportFormatsAndOutputs(
        registry: ModuleRegistry,
        config: Config,
        hasPlatformContext: Boolean,
    ) {
        val planner = FormatOutputPlanner(
            format = null,
            explicitFormat = false,
            outputTarget = null,
            hasPlatformContext = hasPlatformContext,
            logger = NoOpLogger,
        )
        val formats = planner.availableFormats(registry)
        echo(
            "  Formats: ${formats.joinToString(
                ", "
            ) { if (it is ReporterModuleFormat) "${it.formatId} (reporter)" else it.formatId }.ifEmpty { "none" }}"
        )
        echo("  Outputs: ${registry.outputs().joinToString(", ") { it.outputId }.ifEmpty { "none" }}")
        val pairs = planner.selectConfigured(config, registry)
        echo("  Selected: ${pairs.joinToString(", ") { "${it.formatId} -> ${it.outputId}" }.ifEmpty { "none" }}")
    }

    private suspend fun reportPlatformDetection(providers: List<PlatformServiceModule>) {
        echo("[Platform Auto-Detection]")
        val detector = PlatformDetector(providers)
        val detection = detector.detect()
        if (detection != null) {
            echo("  OK: Platform auto-detection working")
            echo("     Platform:   ${detection.platform}")
            echo("     Project ID: ${detection.projectId ?: "not detected"}")
            echo("     MR ID:      ${detection.mrId ?: "not detected"}")
            echo("     Base URL:   ${detection.baseUrl ?: "not detected"}")
            echo("     Token:      ${if (detection.token != null) "present" else "missing"}")
            echo("     Confidence: ${detection.confidence}")
            echo("     Source:     ${detection.source}")
        } else {
            echo("  SKIP: Could not auto-detect platform (non-fatal outside CI)")
            echo("")
            echo("  Suggestions:")
            echo("    • Check git remote: git remote get-url origin")
            echo("    • Set explicit env vars: KARANDASH_PLATFORM, KARANDASH_PROJECT_ID")
            echo("    • Run in CI environment for automatic detection")
        }

        val ciEnv = CIDetector(providers).detect()
        if (ciEnv != null) {
            echo("CI environment: ${ciEnv.platform}")
        }
    }

    /**
     * Reports registered enrichment providers and which enrichment IDs they supply.
     */
    internal fun reportEnrichmentProviders(enrichmentProviders: List<EnrichmentProviderModule>) {
        if (enrichmentProviders.isEmpty()) {
            echo("  SKIP: No enrichment providers registered")
            return
        }
        for (provider in enrichmentProviders) {
            val ids = provider.providedEnrichment
            val idsLabel = if (ids.isEmpty()) "(no enrichment IDs)" else ids.joinToString(", ")
            echo("  ${provider.id}: $idsLabel")
        }
    }

    /**
     * Reports registered AI report modules with their report ID and name.
     */
    internal fun reportAiReports(reports: List<AiReportModule>) {
        if (reports.isEmpty()) {
            echo("  SKIP: No AI report modules registered")
            return
        }
        for (report in reports) {
            echo("  ${report.reportId}: ${report.reportName}")
        }
    }

    /**
     * Reports registered AI metric modules with their metric ID, name, and unit.
     */
    internal fun reportAiMetrics(metrics: List<AiMetricModule>) {
        if (metrics.isEmpty()) {
            echo("  SKIP: No AI metric modules registered")
            return
        }
        for (metric in metrics) {
            echo("  ${metric.metricId}: ${metric.metricName} [${metric.unit}]")
        }
    }

    /**
     * Check 5: Validate `policies:` expressions against the built-in predicate registry.
     *
     * Parses each policy's `when:` expression and reports unknown predicate names.
     */
    internal fun checkPolicies(configResult: DoctorCheckResult): List<DoctorCheckResult> {
        if (configResult !is DoctorCheckResult.Pass) return emptyList()
        val rawConfig = configResult.rawConfig ?: return emptyList()
        val policies = suspendCatching { PolicyConfigParser().parse(rawConfig) }.getOrElse { return emptyList() }
        if (policies.isEmpty()) return emptyList()

        val knownPredicates = buildKnownPredicateNames()
        return policies
            .flatMap { policy -> validatePolicyExpression(policy, knownPredicates) }
            .ifEmpty {
                listOf(DoctorCheckResult.Pass("${policies.size} policy expression(s) valid"))
            }
    }

    /** The predicates a run's `when:` sees: built-ins plus those the loaded rule modules register (`has_tag`, ...). */
    private fun buildKnownPredicateNames(): Set<String> =
        BuiltinPredicates
            .createRegistry(sharedBootstrap.registry(), null)
            .all()
            .map { it.name }
            .toSet()

    private fun validatePolicyExpression(
        policy: PolicyConfig,
        knownPredicates: Set<String>,
    ): List<DoctorCheckResult> {
        val unknowns = extractPredicateNames(policy.condition) - knownPredicates
        return if (unknowns.isEmpty()) {
            emptyList()
        } else {
            unknowns.map { name ->
                DoctorCheckResult.Fail(
                    "Policy expression '${policy.condition}': unknown predicate '$name'",
                    hint = "Replace '$name' with one of: ${knownPredicates.sorted().joinToString(", ")}",
                )
            }
        }
    }

    /**
     * Extracts predicate names from a condition expression string.
     *
     * Matches identifiers that either appear as standalone bare predicates (e.g. `is_draft`)
     * or are immediately followed by `(` (function-call form, e.g. `has_ticket(`).
     * Logical keywords (`and`, `or`, `not`) and numeric literals are excluded.
     * Identifiers inside function-call arguments (e.g. the `feature` in
     * `branch_matches(feature/GLOB)`) are excluded by tracking parenthesis depth.
     */
    private fun extractPredicateNames(expression: String): Set<String> {
        val excluded = setOf("and", "or", "not", "true", "false")
        val result = mutableSetOf<String>()
        val identifierRegex = Regex("""[a-zA-Z_][a-zA-Z0-9_]*""")
        identifierRegex.findAll(expression).forEach { match ->
            val afterMatch = expression.getOrNull(match.range.last + 1)
            val isCallForm = afterMatch == '('
            // Count paren depth at the start of this match
            val depth = expression.substring(0, match.range.first).count { it == '(' } -
                expression.substring(0, match.range.first).count { it == ')' }
            val isTopLevel = depth == 0
            if ((isCallForm || isTopLevel) && match.value !in excluded) {
                result.add(match.value)
            }
        }
        return result
    }

    private inline fun reportResult(
        result: DoctorCheckResult,
        onFailure: () -> Unit
    ) {
        when (result) {
            is DoctorCheckResult.Pass -> {
                echo("  OK: ${result.message}")
            }

            is DoctorCheckResult.Warn -> {
                echo("  WARN: ${result.message}")
                result.hint?.let { echo("    → How to fix: $it") }
            }

            is DoctorCheckResult.Fail -> {
                echoFailure(result.message, result.hint)
                onFailure()
            }

            is DoctorCheckResult.Fatal -> {
                echoFailure(result.message, result.hint)
                onFailure()
            }
        }
    }

    private fun echoFailure(
        message: String,
        hint: String?,
    ) {
        echo("  FAIL: $message")
        hint?.let { echo("    → How to fix: $it") }
    }

    /**
     * Check 1: Discover and parse configuration file.
     */
    @Suppress("TooGenericExceptionCaught") // Config parsing boundary
    internal suspend fun checkConfig(): DoctorCheckResult {
        val discovery = ConfigLoader.discover(sharedBootstrap.formatRegistry, workingDir ?: ConfigDiscovery.defaultStartDir())
        val configFile = discovery.file
            ?: return DoctorCheckResult.Warn(
                "No config file, zero-config defaults apply.",
                hint = "Run 'karandash init' to create one.",
            )

        sharedBootstrap.formatRegistry.getModuleForFile(configFile.toString())
            ?: return DoctorCheckResult.Fatal("Unsupported config format for: ${discovery.matchedPattern}")
        return try {
            // load the config like `validate` does: parents' keys and `auth:` count for doctor too
            val stage = ConfigLoader.forCommand(configFile.toString(), sharedBootstrap).loadConfig()
            val layers = stage.configLayers
            val ruleCount = stage.config.rules.ruleConfigs.size
            DoctorCheckResult.Pass(
                "Found ${discovery.matchedPattern} ($ruleCount rule(s))",
                stage.config,
                stage.rawElement as? JsonObject,
                configFile.safeParent?.toString(),
                authConfig = mergedAuthBlocks(layers.lastOrNull()?.second ?: JsonObject(emptyMap()), layers.dropLast(1)),
                configLayers = layers,
            )
        } catch (e: MissingPluginException) {
            // the config parses; `plugins.on-missing: fail` rejects it, as `validate` would
            DoctorCheckResult.Fail("Missing plugins in ${discovery.matchedPattern}: ${e.message}")
        } catch (e: Exception) {
            // boundary: any config load failure becomes a doctor check result
            e.rethrowCancellation()
            val hint = (e as? ConfigParseException)?.hint ?: (e as? ConfigException)?.hint
            DoctorCheckResult.Fatal("Failed to parse ${discovery.matchedPattern}: ${e.message}", hint = hint)
        }
    }

    /**
     * Check 2: Validate credentials for each configured provider.
     *
     * @param configResult The config check result.
     * @param offline List the configured platforms instead of calling their APIs.
     */
    internal suspend fun checkAuth(
        configResult: DoctorCheckResult,
        offline: Boolean = false,
    ): List<DoctorCheckResult> =
        if (offline) {
            DoctorCommandCommon.listConfiguredPlatforms(configResult)
        } else {
            sharedBootstrap.newRunContexts().let { contexts ->
                val pass = configResult as? DoctorCheckResult.Pass
                contexts.allowLocalhost = pass?.config?.allowLocalhost == true
                NetworkPolicy.setAllowPrivateNetwork(pass?.config?.allowPrivateNetwork == true)
                pass?.configDir?.let { contexts.configDir = it }
                contexts.contentResolver = hostContentResolver(
                    contexts.configDir,
                    (pass?.authConfig ?: pass?.rawConfig)?.let { AuthBlockParser.parse(it, logger = sharedBootstrap.logger) }
                        ?: AuthRegistry.EMPTY,
                    contexts.allowLocalhost,
                    (pass?.config?.limits ?: LimitsConfig()).effectiveFetchTimeout.millis,
                    sharedBootstrap.logger,
                )
                DoctorCommandCommon.checkAuth(configResult, sharedBootstrap.registry().platformServices()) { id, config ->
                    contexts.contextWithConfig(id, config)
                }
            }
        }

    /**
     * Check 3: Load external plugins and report status.
     *
     * Built-in modules are always available; this check focuses on user-installed
     * plugins from the configured plugin directory.
     */
    @Suppress("TooGenericExceptionCaught")
    internal suspend fun checkPlugins(bootstrap: PluginBootstrap,): List<DoctorCheckResult> =
        try {
            val results = mutableListOf<DoctorCheckResult>()

            val loadResult = bootstrap.loadPluginsWithManifest()

            if (loadResult.loaded.isNotEmpty()) {
                results.add(
                    DoctorCheckResult.Pass(
                        "Loaded ${loadResult.loaded.size} plugin(s): ${loadResult.loaded.joinToString(", ")}"
                    )
                )
            }
            for (failure in loadResult.failed) {
                results.add(DoctorCheckResult.Fail(failure.message))
            }

            if (results.isEmpty()) {
                results.add(DoctorCheckResult.Pass("No plugins configured (using built-in rules only)"))
            }

            results
        } catch (e: Exception) {
            // boundary: plugin loaders run third-party code; doctor reports the failure
            e.rethrowCancellation()
            listOf(DoctorCheckResult.Fail("Plugin loading error: ${e.message}"))
        }
}
