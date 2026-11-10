package io.karandash.enrichment.symbol

import io.karandash.models.support.KarandashJson
import io.karandash.sdk.Logger
import io.karandash.sdk.NoOpLogger
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Extracts symbol signatures from source files using `ast-grep run --json=stream`.
 *
 * Each language gets two passes: one for function/method declarations and one
 * for class/struct declarations. Results from both passes are merged and sorted
 * by start line.
 *
 * The extractor is intentionally narrow: it captures names and first-line
 * signatures only, never function bodies. This keeps prompt context lean.
 */
internal object SymbolExtractor {
    private val ndjsonParser: Json = KarandashJson.Default
    private const val NAME_VAR = "\$NAME"

    @Serializable
    private data class LangPattern(
        val pattern: String,
        val nameVar: String,
        val kind: SymbolKind,
    )

    private val PATTERNS: Map<AstGrepLanguage, List<LangPattern>> = mapOf(
        AstGrepLanguage.KOTLIN to listOf(
            LangPattern("fun \$NAME(\$\$\$) { \$\$\$ }", NAME_VAR, SymbolKind.FUNCTION),
            LangPattern("class \$NAME(\$\$\$) { \$\$\$ }", NAME_VAR, SymbolKind.CLASS),
        ),
        AstGrepLanguage.TYPESCRIPT to listOf(
            LangPattern("function \$NAME(\$\$\$) { \$\$\$ }", NAME_VAR, SymbolKind.FUNCTION),
            LangPattern("class \$NAME { \$\$\$ }", NAME_VAR, SymbolKind.CLASS),
        ),
        AstGrepLanguage.JAVASCRIPT to listOf(
            LangPattern("function \$NAME(\$\$\$) { \$\$\$ }", NAME_VAR, SymbolKind.FUNCTION),
            LangPattern("class \$NAME { \$\$\$ }", NAME_VAR, SymbolKind.CLASS),
        ),
        AstGrepLanguage.PYTHON to listOf(
            LangPattern("def \$NAME(\$\$\$):", NAME_VAR, SymbolKind.FUNCTION),
            LangPattern("class \$NAME:", NAME_VAR, SymbolKind.CLASS),
        ),
        AstGrepLanguage.GO to listOf(
            LangPattern("func \$NAME(\$\$\$) { \$\$\$ }", NAME_VAR, SymbolKind.FUNCTION),
            LangPattern("func (\$T) \$NAME(\$\$\$) { \$\$\$ }", NAME_VAR, SymbolKind.METHOD),
        ),
    )

    suspend fun extractFromFile(
        filePath: String,
        language: AstGrepLanguage,
        directory: String,
        binaryPath: String = AstGrepRunner.DEFAULT_BINARY,
        logger: Logger = NoOpLogger,
        runProcess: ProcessRunner = platformProcessRunner,
    ): List<SymbolInfo> {
        val patterns = PATTERNS[language] ?: return emptyList()
        val symbols = mutableListOf<SymbolInfo>()
        for (langPattern in patterns) {
            val output = AstGrepRunner.scan(
                pattern = langPattern.pattern,
                language = language,
                filePath = filePath,
                directory = directory,
                binaryPath = binaryPath,
                logger = logger,
                runProcess = runProcess,
            ) ?: continue
            symbols.addAll(parseMatches(output, langPattern.kind, langPattern.nameVar, filePath, logger))
        }
        return symbols.sortedBy { it.startLine }
    }

    private fun parseMatches(
        ndjson: String,
        kind: SymbolKind,
        nameVar: String,
        filePath: String,
        logger: Logger,
    ): List<SymbolInfo> {
        val result = mutableListOf<SymbolInfo>()
        for (line in ndjson.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || !trimmed.startsWith("{")) continue
            try {
                val match = ndjsonParser.decodeFromString<AstGrepMatch>(trimmed)
                val name = match.metaVariables[nameVar]?.text ?: continue
                val signature = match.text
                    .lines()
                    .firstOrNull()
                    ?.trim() ?: continue
                result.add(
                    SymbolInfo(
                        kind = kind,
                        name = name,
                        signature = signature,
                        startLine = match.range.start.line,
                    ),
                )
            } catch (
                @Suppress("TooGenericExceptionCaught")
                e: Exception, // boundary: malformed NDJSON line is skipped
            ) {
                logger.debug("ast-grep: skipping unparseable match line in $filePath: ${e.message}")
            }
        }
        return result
    }
}
