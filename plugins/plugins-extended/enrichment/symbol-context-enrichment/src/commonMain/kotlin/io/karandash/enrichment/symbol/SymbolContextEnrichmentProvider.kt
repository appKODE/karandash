package io.karandash.enrichment.symbol

import io.karandash.api.PluginContext
import io.karandash.api.context.EnrichmentContext
import io.karandash.api.module.EnrichmentProviderModule
import io.karandash.models.MergeRequest
import io.karandash.models.support.KarandashJson
import io.karandash.sdk.Logger
import io.karandash.sdk.NoOpLogger
import io.karandash.sdk.enrichment.EnrichmentConfigParser
import io.karandash.sdk.enrichment.EnrichmentKeys
import kotlinx.atomicfu.atomic
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** One ast-grep process runs per pattern per file; this bounds the cost on very large merge requests. */
internal const val MAX_SCANNED_FILES = 100

/** Wall-clock cap for the whole scan: slow files or a slow disk cannot hold the run past it. */
internal val SCAN_TIME_BUDGET: Duration = 60.seconds

class SymbolContextEnrichmentProvider internal constructor(
    private val runProcess: ProcessRunner,
    private val timeBudget: Duration = SCAN_TIME_BUDGET,
) : EnrichmentProviderModule {
    constructor() : this(platformProcessRunner)

    override val id: String = ID
    override val name: String = "Symbol Context Enrichment"
    override val version: String = "1.0.0"
    override val dslName: String = CONFIG_KEY
    override val configKey: String = CONFIG_KEY
    override val defaultEnabled: Boolean = false
    override val defaultPriority: Int = 100
    override val description: String =
        "Extracts symbol signatures from changed files via ast-grep for AI context."

    private var logger: Logger = NoOpLogger
    private val symbolConfig = atomic<SymbolContextConfig>(SymbolContextConfig())
    private val available = atomic<Boolean>(false)
    private val workingDirectory = atomic<String>("")
    private val skip = atomic<String?>(null)

    override val providedEnrichment: Set<String> get() = setOf(EnrichmentKeys.SYMBOLS_CHANGED)

    override fun requiredEnrichment(): List<String> = emptyList()

    override suspend fun initialize(context: PluginContext): Boolean {
        logger = context.logger
        workingDirectory.value = context.workingDirectory
        symbolConfig.value = decodeConfig(context.pluginConfig)
        skip.value = null

        if (!symbolConfig.value.changed.enabled) {
            logger.debug("Symbol context enrichment: disabled via config")
            return false
        }

        available.value = AstGrepRunner.probe(
            binaryPath = symbolConfig.value.astGrepPath,
            logger = logger,
            runProcess = runProcess,
        )
        if (!available.value) {
            skip.value = "ast-grep binary '${symbolConfig.value.astGrepPath}' not found on PATH"
            logger.info(
                "Symbol context enrichment: ast-grep binary '${symbolConfig.value.astGrepPath}' " +
                    "not found on PATH — provider disabled for this run",
            )
            return false
        }

        logger.debug("Symbol context enrichment: initialized with ast-grep at '${symbolConfig.value.astGrepPath}'")
        return true
    }

    override fun skipReason(): String? = skip.value

    override suspend fun shutdown() {
        available.value = false
        workingDirectory.value = ""
    }

    override suspend fun enrich(
        mr: MergeRequest,
        config: JsonElement,
        upstreamContext: EnrichmentContext,
    ): Map<String, JsonElement> {
        if (!available.value) return emptyMap()
        val directory = workingDirectory.value.ifBlank { null } ?: return emptyMap()

        val changedPaths = mr.changes.map { it.path }.filter { it.isNotBlank() }
        if (changedPaths.isEmpty()) return emptyMap()

        val symbolsByFile = mutableMapOf<String, JsonElement>()
        if (changedPaths.size > MAX_SCANNED_FILES) {
            logger.warn("Symbol context enrichment: ${changedPaths.size} changed files, scanning only the first $MAX_SCANNED_FILES")
        }
        val started = TimeSource.Monotonic.markNow()
        for (path in changedPaths.take(MAX_SCANNED_FILES)) {
            if (started.elapsedNow() >= timeBudget) {
                logger.warn("Symbol context enrichment: time budget of $timeBudget used up, ${symbolsByFile.size} file(s) scanned")
                break
            }
            val language = AstGrepLanguage.fromFilePath(path) ?: continue
            val symbols = SymbolExtractor.extractFromFile(
                filePath = path,
                language = language,
                directory = directory,
                binaryPath = symbolConfig.value.astGrepPath,
                logger = logger,
                runProcess = runProcess,
            )
            if (symbols.isEmpty()) continue
            symbolsByFile[path] = KarandashJson.Default.encodeToJsonElement(
                ListSerializer(SymbolInfo.serializer()),
                symbols,
            )
        }

        if (symbolsByFile.isEmpty()) return emptyMap()
        logger.debug("Symbol context enrichment: extracted symbols from ${symbolsByFile.size} file(s)")
        return mapOf(EnrichmentKeys.SYMBOLS_CHANGED to JsonObject(symbolsByFile))
    }

    private fun decodeConfig(config: JsonElement): SymbolContextConfig =
        EnrichmentConfigParser.decodeOrDefault(
            config,
            SymbolContextConfig.serializer(),
            KNOWN_KEYS,
            ID,
            logger::warn
        ) { SymbolContextConfig() }

    companion object {
        const val ID = "symbol-context"
        const val CONFIG_KEY = ID
        private val KNOWN_KEYS = setOf("ast-grep-path", "changed")
    }
}
