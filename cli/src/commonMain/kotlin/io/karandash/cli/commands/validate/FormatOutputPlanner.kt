package io.karandash.cli.commands.validate

import com.github.ajalt.clikt.core.ProgramResult
import io.karandash.api.ModuleRegistry
import io.karandash.api.finding.FormattedReport
import io.karandash.api.finding.ValidationReport
import io.karandash.api.module.FormatModule
import io.karandash.api.module.OutputModule
import io.karandash.api.module.ReporterModule
import io.karandash.api.support.rethrowCancellation
import io.karandash.bootstrap.ModuleSelector
import io.karandash.cli.errors.ExitCodes
import io.karandash.config.Config
import io.karandash.config.DisplayConsoleConfig
import io.karandash.config.ReportConfig
import io.karandash.config.ReportTransparencyConfig
import io.karandash.config.WalkthroughMode
import io.karandash.config.keys.ModuleIds
import io.karandash.config.pipeline.isModuleDisabled
import io.karandash.detect.SmartReporterDefaults
import io.karandash.models.report.ReportStyle
import io.karandash.models.support.KarandashJson
import io.karandash.sdk.Logger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.time.TimeSource

/**
 * A resolved format+output pair as plain ids and configs (no live modules), for diagnostics and `doctor`.
 */
@Serializable
internal data class ConfiguredFormatOutput(
    val formatId: String,
    val outputId: String,
    val formatConfig: JsonElement,
    val outputConfig: JsonElement,
) {
    companion object {
        fun from(
            formatModule: FormatModule,
            outputModule: OutputModule,
            formatConfig: JsonElement,
            outputConfig: JsonElement,
        ): Pair<ConfiguredFormatOutput, Pair<FormatModule, OutputModule>> =
            ConfiguredFormatOutput(
                formatId = formatModule.formatId,
                outputId = outputModule.outputId,
                formatConfig = formatConfig,
                outputConfig = outputConfig,
            ) to (formatModule to outputModule)
    }
}

/**
 * Plans format and output module selection based on CLI flags, config, and platform context.
 *
 * [formatFlagOverrides] (CLI `-q`/`-v`/`--explain`, only the flags that are on) are laid over every format config.
 *
 * Auto-detection rules:
 * - No platform context → interactive-default format + interactive-default output
 * - Platform context available → platform-default format + platform-default output
 * - Explicit `--format` flag → uses that format + interactive-default output
 * - Explicit `--output` matching a known output ID → uses that output + auto-detected format
 * - Explicit `--output` not matching any output ID → treated as a file path → filesystem output
 * - Configured `reporters:` block entries → activates each configured format+output pair
 *   (the config key is `reporters:`)
 *
 * Without `--format` and without a `reporters:` block, [SmartReporterDefaults] (`isDefaultCI`/`isDefaultInteractive`/
 * `isDefaultReporter` of the registered [ReporterModule]s) picks the format when one of them is registered.
 * With [liveMode] (`--watch`) a [ReporterModule] whose `supportsLiveMode` is false is skipped with a warning.
 * The pairs are available as [ConfiguredFormatOutput] carriers through [selectConfigured].
 *
 * @lifecycle per-run -- do not reuse across invocations
 */
internal class FormatOutputPlanner(
    private val format: String?,
    private val explicitFormat: Boolean,
    private val outputTarget: String?,
    private val hasPlatformContext: Boolean,
    private val logger: Logger,
    private val formatFlagOverrides: JsonObject = JsonObject(emptyMap()),
    private val liveMode: Boolean = false,
) {
    private companion object {
        private const val CONFIG_KEY = "config"
        private const val DEFAULT_FORMAT_ID = "json"
        private val EMPTY_JSON_OBJECT = JsonObject(emptyMap())
        private const val LANGUAGE_KEY = "language"
        private const val STYLE_KEY = "style"
        private val RESERVED_ENTRY_KEYS = setOf("format", "output", "enabled", CONFIG_KEY)
        private const val SUCCESS_MARK_KEY = "successMark"
        private const val TRANSPARENCY_KEY = "transparency"
        private const val WALKTHROUGH_KEY = "walkthrough"
        private const val FAILURE_MARK_KEY = "failureMark"
        private const val OUTPUT_FILE_KEY = "outputFile"
    }

    /** True once any format or output module failed in [runPairs]; the run then cannot exit successfully. */
    var outputFailed: Boolean = false
        private set

    /**
     * Select format+output pairs to execute, returning live module references alongside
     * their serializable config carriers.
     *
     * Returns a list of [ResolvedFormatOutput] each holding live module references and configs.
     */
    fun selectPairs(
        config: Config,
        registry: ModuleRegistry,
    ): List<ResolvedFormatOutput> {
        val allFormats = availableFormats(registry)
        val allOutputs = registry.outputs()

        if (allFormats.isEmpty() || allOutputs.isEmpty()) return emptyList()

        val pairs = mutableListOf<ResolvedFormatOutput>()

        val smartDefault = SmartReporterDefaults(registry)
            .takeIf { format == null && config.reporters.isNullOrEmpty() }
            ?.resolveDefaultFormat(isCI = hasPlatformContext)
        val primaryFormat = resolvePrimaryFormat(allFormats, smartDefault)
        val primaryOutput = resolvePrimaryOutput(allOutputs)?.let { output ->
            if (primaryFormat == null || output.accepts(primaryFormat.formatId)) return@let output
            if (outputTarget != null) {
                logger.warn(
                    "Output '${output.outputId}' does not accept format '${primaryFormat.formatId}'; writing to the console instead"
                )
            }
            allOutputs.firstOrNull { it.isDefaultInteractive && it.accepts(primaryFormat.formatId) }
        }

        if (primaryFormat != null && primaryOutput != null) {
            val outputConfig = resolveOutputConfigForPrimary(primaryOutput, config, allOutputs)
            pairs.add(
                ResolvedFormatOutput(
                    formatModule = primaryFormat,
                    outputModule = primaryOutput,
                    formatConfig = resolveFormatConfig(primaryFormat, config),
                    outputConfig = outputConfig,
                )
            )
        }

        // Secondary pairs from the config reporters/outputs block - only when --format was not
        // passed explicitly (explicit --format suppresses all secondary outputs).
        // The config key is `reporters:`.
        if (!explicitFormat) {
            pairs.addAll(
                selectConfiguredOutputs(
                    config = config,
                    allFormats = allFormats,
                    allOutputs = allOutputs,
                    primaryFormatId = primaryFormat?.formatId,
                    primaryOutputId = primaryOutput?.outputId,
                )
            )
        }

        val defaults = listOfNotNull(
            config.report.language
                .takeIf { it != ReportConfig.DEFAULT_LANGUAGE }
                ?.let { LANGUAGE_KEY to it },
            config.report.style
                .takeIf { it != ReportConfig.DEFAULT_STYLE && knownStyle(it) }
                ?.let { STYLE_KEY to it },
        )
        return if (defaults.isEmpty()) pairs else pairs.map { it.withDefaults(defaults) }
    }

    /** Whether [style] names a [ReportStyle]; an unknown one is warned about, and every format keeps its own default. */
    private fun knownStyle(style: String): Boolean =
        (ReportStyle.parse(style) != null).also { known ->
            if (!known) {
                val names = ReportStyle.entries.joinToString(", ") { it.key }
                logger.warn("Unknown report.style '$style' (expected one of: $names); using ${ReportStyle.DEFAULT.key}")
            }
        }

    /** [selectPairs] as [ConfiguredFormatOutput] carriers. */
    fun selectConfigured(
        config: Config,
        registry: ModuleRegistry,
    ): List<ConfiguredFormatOutput> = selectPairs(config, registry).map { it.toConfigured() }

    /** Every format a run can use: the registered format modules plus the runnable [ReporterModule]s. */
    fun availableFormats(registry: ModuleRegistry): List<FormatModule> =
        registry.formats().let {
            it +
                reporterModuleFormats(registry.reporters(), it)
        }

    /**
     * [ResolvedFormatOutput] with `report.language` and `report.style` as the `language` and `style` of each config
     * that does not set its own.
     */
    private fun ResolvedFormatOutput.withDefaults(defaults: List<Pair<String, String>>): ResolvedFormatOutput {
        fun JsonElement.with(): JsonElement =
            if (this is JsonObject) {
                JsonObject(defaults.filter { it.first !in this }.associate { it.first to JsonPrimitive(it.second) } + this)
            } else {
                this
            }
        return ResolvedFormatOutput(formatModule, outputModule, formatConfig.with(), outputConfig.with())
    }

    /**
     * Execute all format+output pairs against [report], running them in parallel.
     *
     * @return the first non-null body produced (for stdout/filesystem consumers), or null.
     */
    suspend fun runPairs(
        pairs: List<ResolvedFormatOutput>,
        report: ValidationReport,
        onTimed: suspend (ResolvedFormatOutput, Long, Long) -> Unit = { _, _, _ -> },
    ): String? =
        coroutineScope {
            val results = pairs.map { pair -> async { runSinglePair(pair, report, onTimed) } }.awaitAll()
            // written once, after every pair finished: the pairs themselves share no state
            outputFailed = results.any { it.failed }
            results.firstNotNullOfOrNull { it.body }
        }

    /** Registered [ReporterModule]s as formats ([ReporterModuleFormat]); a format id already taken by a format module wins. */
    private fun reporterModuleFormats(
        reporters: List<ReporterModule>,
        formats: List<FormatModule>,
    ): List<FormatModule> =
        reporters.mapNotNull { reporter ->
            when {
                resolveFormatById(reporter.formatId, formats) != null -> {
                    logger.warn("Reporter '${reporter.id}': format id '${reporter.formatId}' is taken by a format module; reporter not run")
                    null
                }

                liveMode && !reporter.supportsLiveMode -> {
                    logger.warn("Reporter '${reporter.id}' does not support live mode (--watch); not run")
                    null
                }

                reporter.requiresPlatformContext && !hasPlatformContext -> {
                    logger.debug("FormatOutputPlanner: reporter '${reporter.id}' requires platform context; not run")
                    null
                }

                else -> {
                    ReporterModuleFormat(reporter, formatFlagOverrides)
                }
            }
        }

    @Suppress("TooGenericExceptionCaught") // boundary: format+output execution is non-fatal per pair
    private suspend fun runSinglePair(
        pair: ResolvedFormatOutput,
        report: ValidationReport,
        onTimed: suspend (ResolvedFormatOutput, Long, Long) -> Unit,
    ): PairResult {
        val start = TimeSource.Monotonic.markNow()
        val (hidden, formatConfig) = splitHideFields(pair.formatConfig)
        hidden.warnings.forEach { logger.warn("Format '${pair.formatModule.formatId}': $it") }
        val language = ((formatConfig as? JsonObject)?.get(LANGUAGE_KEY) as? JsonPrimitive)?.contentOrNull
        val formatted: FormattedReport = try {
            pair.formatModule.format(report.without(hidden.fields).inLanguage(language), formatConfig)
        } catch (e: Exception) {
            // boundary: format/output module is plugin code
            e.rethrowCancellation()
            logger.error("Format module '${pair.formatModule.formatId}' failed", e)
            onTimed(pair, start.elapsedNow().inWholeMilliseconds, 0)
            return PairResult(body = null, failed = true)
        }
        val formatMs = start.elapsedNow().inWholeMilliseconds

        val outputStart = TimeSource.Monotonic.markNow()
        var outputError = false
        try {
            pair.outputModule.output(formatted, pair.outputConfig)
        } catch (e: Exception) {
            // boundary: format/output module is plugin code
            e.rethrowCancellation()
            logger.error("Output module '${pair.outputModule.outputId}' failed", e)
            outputError = true
        }
        onTimed(pair, formatMs, outputStart.elapsedNow().inWholeMilliseconds)

        return PairResult(body = formatted.body, failed = outputError)
    }

    private fun resolvePrimaryFormat(
        allFormats: List<FormatModule>,
        smartDefault: String?,
    ): FormatModule? {
        if (format != null) {
            val normalized = ModuleSelector.normalizeKey(format)
            val match = allFormats.firstOrNull { ModuleSelector.normalizeKey(it.formatId) == normalized }
            if (match == null) {
                val available = allFormats.map { it.formatId }.sorted().joinToString(", ")
                logger.error(
                    "Unknown output format '$format'. " +
                        "Available formats: $available"
                )
                throw ProgramResult(ExitCodes.CONFIG_ERROR)
            }
            return match
        }
        smartDefault?.let { resolveFormatById(it, allFormats) }?.let { return it }
        return if (hasPlatformContext) {
            allFormats.firstOrNull { it.isDefaultPlatform }
        } else {
            allFormats.firstOrNull { it.isDefaultInteractive }
        }
    }

    private fun resolvePrimaryOutput(allOutputs: List<OutputModule>): OutputModule? {
        val default = if (hasPlatformContext) {
            allOutputs.firstOrNull { it.isDefaultPlatform }
        } else {
            allOutputs.firstOrNull { it.isDefaultInteractive }
        }
        if (outputTarget == null) return default
        val normalized = ModuleSelector.normalizeKey(outputTarget)
        // Not a known output ID - a file path, written only by an output that supports file output
        val chosen = allOutputs.firstOrNull { ModuleSelector.normalizeKey(it.outputId) == normalized }
            ?: allOutputs.firstOrNull { it.supportsFileOutput }
            ?: run {
                logger.warn("--output '$outputTarget': no output module supports file output; using the default output")
                return default
            }
        if (chosen.requiresPlatformContext && !hasPlatformContext) {
            logger.debug("FormatOutputPlanner: output '${chosen.outputId}' requires platform context; using the interactive output")
            return allOutputs.firstOrNull { it.isDefaultInteractive }
        }
        return chosen
    }

    private fun selectConfiguredOutputs(
        config: Config,
        allFormats: List<FormatModule>,
        allOutputs: List<OutputModule>,
        primaryFormatId: String?,
        primaryOutputId: String?,
    ): List<ResolvedFormatOutput> {
        // The config key is `reporters:`.
        val outputsConfig = config.reporters ?: return emptyList()
        return outputsConfig.mapNotNull { (key, blockElement) ->
            val name = ModuleIds.REPORTER_ALIASES[key] ?: key
            val blockObj = blockElement as? JsonObject
            if (blockObj == null || isModuleDisabled(blockObj)) return@mapNotNull null
            val (formatId, target) = entryTarget(name, blockObj, allFormats, allOutputs)
            // `output:` naming no output module is a file path for the entry's format (`junit: { output: junit.xml }`)
            val filePath = target.takeIf { formatId != null && resolveOutputById(it, allOutputs) == null }
            val outputId = filePath?.let { allOutputs.firstOrNull { it.supportsFileOutput }?.outputId } ?: target
            // Skip if this pair duplicates the primary
            if (filePath == null && formatId == primaryFormatId && outputId == primaryOutputId) return@mapNotNull null
            resolveEntry(name, formatId, outputId, allFormats, allOutputs)?.let { (formatModule, outputModule) ->
                val entry = entryConfig(blockObj)
                ResolvedFormatOutput(
                    formatModule = formatModule,
                    outputModule = outputModule,
                    formatConfig = resolveFormatConfig(formatModule, config),
                    outputConfig = filePath?.let { JsonObject(entry + (OUTPUT_FILE_KEY to JsonPrimitive(it))) } ?: entry,
                )
            }
        }
    }

    /** The format and output modules for `reporters.<name>`, or null (logged) when the entry cannot run. */
    private fun resolveEntry(
        name: String,
        formatId: String?,
        outputId: String,
        allFormats: List<FormatModule>,
        allOutputs: List<OutputModule>,
    ): Pair<FormatModule, OutputModule>? {
        val outputModule = resolveOutputById(outputId, allOutputs)
            ?: return skipEntry(name, "no output module for id '$outputId'")
        val resolvedFormatId = formatId ?: defaultFormatFor(outputModule)
        val formatModule = resolveFormatById(resolvedFormatId, allFormats)
            ?: return skipEntry(name, "no format module for id '$resolvedFormatId'")
        if (!outputModule.accepts(formatModule.formatId)) {
            logger.warn(
                "reporters.$name: output '${outputModule.outputId}' does not accept format " +
                    "'${formatModule.formatId}' (accepts: ${outputModule.acceptedFormats.sorted().joinToString()}); skipped"
            )
            return null
        }
        if (outputModule.requiresPlatformContext && !hasPlatformContext) {
            return skipEntry(name, "skipping output '${outputModule.outputId}' — requires platform context")
        }
        return formatModule to outputModule
    }

    private fun skipEntry(
        name: String,
        reason: String,
    ): Nothing? {
        logger.debug("FormatOutputPlanner: $reason (entry '$name')")
        return null
    }

    /**
     * `(formatId, outputId)` for a `reporters:` entry. The entry name is the output id unless `output:` is set;
     * a name that is only a format id (`sarif: true`) is format shorthand and goes to the interactive output.
     */
    private fun entryTarget(
        name: String,
        block: JsonObject,
        allFormats: List<FormatModule>,
        allOutputs: List<OutputModule>,
    ): Pair<String?, String> {
        val formatId = (block["format"] as? JsonPrimitive)?.content
        val outputId = (block["output"] as? JsonPrimitive)?.content
        if (outputId != null) {
            val isFilePath = resolveOutputById(outputId, allOutputs) == null && resolveFormatById(name, allFormats) != null
            return (formatId ?: name.takeIf { isFilePath }) to outputId
        }
        if (resolveOutputById(name, allOutputs) != null || resolveFormatById(name, allFormats) == null) {
            return formatId to name
        }
        val interactive = allOutputs.firstOrNull { it.isDefaultInteractive }?.outputId ?: name
        return (formatId ?: name) to interactive
    }

    /** Output's preferred format, else `json`, else the first format it accepts. */
    private fun defaultFormatFor(output: OutputModule): String =
        output.preferredFormat
            ?: DEFAULT_FORMAT_ID.takeIf { output.accepts(it) }
            ?: output.acceptedFormats.sorted().firstOrNull()
            ?: DEFAULT_FORMAT_ID

    /** Entry config: flat keys (minus `format`/`output`/`enabled`/`config`) overlaid by the nested `config:` block. */
    private fun entryConfig(block: JsonObject): JsonObject {
        val flat = block.filterKeys { it !in RESERVED_ENTRY_KEYS }
        val nested = block[CONFIG_KEY] as? JsonObject ?: return JsonObject(flat)
        return JsonObject(flat + nested)
    }

    private fun OutputModule.accepts(formatId: String): Boolean =
        acceptedFormats.isEmpty() ||
            acceptedFormats.any { ModuleSelector.normalizeKey(it) == ModuleSelector.normalizeKey(formatId) }

    private fun resolveFormatById(
        formatId: String,
        allFormats: List<FormatModule>
    ): FormatModule? {
        val normalized = ModuleSelector.normalizeKey(formatId)
        return allFormats.firstOrNull { ModuleSelector.normalizeKey(it.formatId) == normalized }
    }

    private fun resolveOutputById(
        outputId: String,
        allOutputs: List<OutputModule>
    ): OutputModule? {
        val normalized = ModuleSelector.normalizeKey(outputId)
        return allOutputs.firstOrNull { ModuleSelector.normalizeKey(it.outputId) == normalized }
    }

    private fun resolveFormatConfig(
        format: FormatModule,
        config: Config
    ): JsonElement {
        // The config key is `reporters:`.
        val block = config.reporters?.get(format.formatId) as? JsonObject
        val configured = if (block == null || isModuleDisabled(block)) EMPTY_JSON_OBJECT else entryConfig(block)
        val displayDefaults = if (format.isDefaultInteractive) consoleMarkDefaults(config.display.console) else emptyMap()
        val reportDefaults = if (format.isDefaultPlatform) reportDefaults(config.report) else emptyMap()
        return JsonObject(displayDefaults + reportDefaults + configured + formatFlagOverrides)
    }

    /** `report:` keys for the platform format (the MR comment); its own `reporters:` block wins. Unchanged → nothing. */
    private fun reportDefaults(report: ReportConfig): Map<String, JsonElement> {
        val defaults = ReportConfig()
        return buildMap {
            if (report.transparency != defaults.transparency) {
                put(TRANSPARENCY_KEY, KarandashJson.Default.encodeToJsonElement(ReportTransparencyConfig.serializer(), report.transparency))
            }
            if (report.walkthrough != defaults.walkthrough) {
                put(WALKTHROUGH_KEY, KarandashJson.Default.encodeToJsonElement(WalkthroughMode.serializer(), report.walkthrough))
            }
        }
    }

    /** `display.console` marks as defaults for the interactive format; its own `reporters:` block wins. Unset → nothing. */
    private fun consoleMarkDefaults(console: DisplayConsoleConfig): Map<String, JsonElement> {
        val defaults = DisplayConsoleConfig()
        return buildMap {
            if (console.successMark != defaults.successMark) put(SUCCESS_MARK_KEY, JsonPrimitive(console.successMark))
            if (console.failureMark != defaults.failureMark) put(FAILURE_MARK_KEY, JsonPrimitive(console.failureMark))
        }
    }

    /**
     * Resolve output config for the primary output. When `--output` specifies a file path
     * (not a known output ID), injects `outputFile` into the filesystem output config.
     */
    private fun resolveOutputConfigForPrimary(
        output: OutputModule,
        config: Config,
        allOutputs: List<OutputModule>,
    ): JsonElement {
        if (outputTarget != null && output.supportsFileOutput) {
            val isKnownOutputId = allOutputs.any { ModuleSelector.normalizeKey(it.outputId) == ModuleSelector.normalizeKey(outputTarget) }
            if (!isKnownOutputId) {
                // outputTarget is a file path - inject it as outputFile
                return JsonObject(mapOf(OUTPUT_FILE_KEY to JsonPrimitive(outputTarget)))
            }
        }
        return resolveOutputConfig(output, config)
    }

    private fun resolveOutputConfig(
        output: OutputModule,
        config: Config
    ): JsonElement {
        // The config key is `reporters:`.
        val block = config.reporters?.get(output.outputId) as? JsonObject ?: return EMPTY_JSON_OBJECT
        return if (isModuleDisabled(block)) EMPTY_JSON_OBJECT else entryConfig(block)
    }
}

private class PairResult(
    val body: String?,
    val failed: Boolean,
)

/**
 * A resolved format+output pair holding live module references and their runtime configs.
 *
 * Not serializable - holds live module instances.
 */
internal class ResolvedFormatOutput(
    val formatModule: FormatModule,
    val outputModule: OutputModule,
    val formatConfig: JsonElement,
    val outputConfig: JsonElement,
)

/** This pair as a [ConfiguredFormatOutput] carrier. */
internal fun ResolvedFormatOutput.toConfigured(): ConfiguredFormatOutput =
    ConfiguredFormatOutput.from(formatModule, outputModule, formatConfig, outputConfig).first
