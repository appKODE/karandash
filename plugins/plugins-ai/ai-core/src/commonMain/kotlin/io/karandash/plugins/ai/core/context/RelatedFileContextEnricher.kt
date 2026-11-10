package io.karandash.plugins.ai.core.context

import io.karandash.api.PluginContext
import io.karandash.api.ai.AiContextBlock
import io.karandash.api.ai.AiContextEnricherContext
import io.karandash.api.ai.AiContextEnricherModule
import io.karandash.api.ai.BlockBudget
import io.karandash.logger.Logger
import io.karandash.logger.NoOpLogger
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.platform.exists
import io.karandash.platform.safeName
import io.karandash.plugins.ai.core.rules.RelatedFileSources
import io.karandash.plugins.ai.core.rules.RelatedFilesConfig
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * Discovers test files corresponding to each changed source file using path-heuristics
 * (no subprocess required). Reports the found test paths as a Markdown block so the AI
 * reviewer can assess whether the changes are covered by tests without having to infer
 * paths on its own. With `sources.callers: true` it also lists the call sites of the changed or removed symbols
 * ([CallerSearch]).
 *
 * Disabled by default - opt-in via `ai.context-quality.related-files.enabled: true` (per rule via
 * `rule-context`).
 */
class RelatedFileContextEnricher : AiContextEnricherModule {
    override val id: String = MODULE_ID
    override val name: String = MODULE_NAME
    override val version: String = MODULE_VERSION
    override val description: String = MODULE_DESCRIPTION
    override val dslName: String = DSL_NAME
    override val enricherPriority: Int = ENRICHER_PRIORITY
    override val defaultEnabled: Boolean = false

    private val workingDirectory = atomic<String>("")
    private var logger: Logger = NoOpLogger

    /** Directory (repo-relative) to the file names in it, listed once per run: probes scale with directories, not candidates. */
    private val listings = atomic(emptyMap<String, Set<String>>())

    override suspend fun initialize(context: PluginContext): Boolean {
        workingDirectory.value = context.workingDirectory
        logger = context.logger
        listings.value = emptyMap()
        return true
    }

    override suspend fun shutdown() { /* no resources */ }

    override suspend fun enrich(
        mr: MergeRequest,
        context: AiContextEnricherContext,
    ): Map<String, String> = emptyMap()

    override suspend fun provideBlock(
        mr: MergeRequest,
        changes: List<FileChange>,
        config: JsonElement,
        budget: BlockBudget,
    ): AiContextBlock? {
        if (!isRelatedFileContextEnabled(config)) return null
        if (changes.isEmpty()) return null
        val changedPaths = changes.map { it.path }.toSet()
        val found = testFiles(changes, changedPaths)
        val tests = if (found.isEmpty()) {
            ""
        } else {
            // whole lines only, within the block budget (heading included), so callers get the rest or nothing
            val room = budget.maxChars - CALLERS_HEADING.length
            found
                .map { (source, test) -> "\n  - $source → $test" }
                .runningFold(TESTS_INTRO) { acc, line -> acc + line }
                .lastOrNull { it.length <= room }
                ?.takeIf { it != TESTS_INTRO }
                .orEmpty()
        }
        val callers = if (isCallersSearchEnabled(config)) {
            val maxChars = minOf(CALLERS_MAX_CHARS, budget.maxChars - CALLERS_HEADING.length - tests.length - 2)
            withContext(Dispatchers.IO) {
                CallerSearch.search(workingDirectory.value, CallerSearch.symbols(changes), changedPaths, maxChars) { logger.warn(it) }
            }
        } else {
            null
        }
        val body = listOfNotNull(tests.ifEmpty { null }, callers).joinToString("\n\n")
        if (body.isEmpty()) return null

        return AiContextBlock(
            heading = if (callers == null) BLOCK_HEADING else CALLERS_HEADING,
            content = body,
            priority = BLOCK_PRIORITY,
            source = id,
        )
    }

    private fun exists(candidate: String): Boolean {
        if (workingDirectory.value.isBlank()) return false
        val dir = candidate.substringBeforeLast('/', "")
        val names = listings.value[dir] ?: list(dir).also { found ->
            // bounded: past the cap a directory is listed again rather than remembered
            if (listings.value.size < MAX_LISTED_DIRS) listings.update { it + (dir to found) }
        }
        return candidate.substringAfterLast('/') in names
    }

    private fun list(dir: String): Set<String> {
        val path = if (dir.isEmpty()) Path(workingDirectory.value) else Path(workingDirectory.value, dir)
        return try {
            if (path.exists()) SystemFileSystem.list(path).map { it.safeName }.toSet() else emptySet()
        } catch (e: IOException) {
            logger.warn("related-file-context: cannot list '$dir': ${e.message}")
            emptySet()
        }
    }

    /** The first existing test candidate of each changed file that is not itself in the diff. */
    private fun testFiles(
        changes: List<FileChange>,
        changedPaths: Set<String>,
    ): List<Pair<String, String>> {
        if (workingDirectory.value.isBlank()) return emptyList()
        return changes.mapNotNull { change ->
            testCandidates(change.path)
                .firstOrNull { it !in changedPaths && exists(it) }
                ?.let { change.path to it }
        }
    }

    companion object {
        private const val MAX_LISTED_DIRS: Int = 4_096
        const val MODULE_ID: String = "related-file-context"
        const val MODULE_NAME: String = "Related File Context"
        const val MODULE_VERSION: String = "1.0.0"
        const val MODULE_DESCRIPTION: String = "Discovers test files related to changed source files"
        const val DSL_NAME: String = "relatedFileContext"
        const val ENRICHER_PRIORITY: Int = 120
        const val BLOCK_PRIORITY: Int = 120
        const val BLOCK_HEADING: String = "#### Related test files"
        const val CALLERS_HEADING: String = "#### Related files"
        const val CALLERS_MAX_CHARS: Int = 8_000
        private const val TESTS_INTRO: String = "Test files for changed sources:"
        const val CONTEXT_QUALITY_KEY: String = "contextQuality"
        const val RELATED_FILES_KEY: String = "relatedFiles"
    }
}

/**
 * Reads `contextQuality.relatedFiles.enabled`, which the AI prompt assembler overwrites with the rule's
 * resolved [RelatedFilesConfig] (per-rule override, alias and `contextQuality.enabled` applied).
 */
internal fun isRelatedFileContextEnabled(config: JsonElement): Boolean =
    (relatedFilesBlock(config)?.get("enabled") as? JsonPrimitive)?.booleanOrNull ?: RelatedFilesConfig().enabled

/** Reads `contextQuality.relatedFiles.sources.callers`, also written by the AI prompt assembler. */
internal fun isCallersSearchEnabled(config: JsonElement): Boolean {
    val sources = relatedFilesBlock(config)?.get("sources") as? JsonObject
    return (sources?.get("callers") as? JsonPrimitive)?.booleanOrNull ?: RelatedFileSources().callers
}

private fun relatedFilesBlock(config: JsonElement): JsonObject? {
    val contextQuality = (config as? JsonObject)?.get(RelatedFileContextEnricher.CONTEXT_QUALITY_KEY) as? JsonObject
    return contextQuality?.get(RelatedFileContextEnricher.RELATED_FILES_KEY) as? JsonObject
}

/**
 * Returns ordered candidate test file paths for a given source path using common
 * project layout conventions (Maven/Gradle, flat, Python, JavaScript/TypeScript).
 */
internal fun testCandidates(sourcePath: String): List<String> {
    val candidates = mutableListOf<String>()
    val fileName = sourcePath.substringAfterLast('/')
    val dir = sourcePath.substringBeforeLast('/', "")

    // Kotlin / Java - Maven / Gradle standard layout.
    // Accepts both `src/main/...` (relative) and `/src/main/...` or `module/src/main/...` forms.
    val mavenTestPath: String? = when {
        sourcePath.startsWith("src/main/") -> sourcePath.replaceFirst("src/main/", "src/test/")
        sourcePath.contains("/src/main/") -> sourcePath.replaceFirst("/src/main/", "/src/test/")
        else -> null
    }
    if (mavenTestPath != null) {
        val mavenStem = fileName.substringBeforeLast('.')
        val mavenExt = fileName.substringAfterLast('.', "")
        val testDir = mavenTestPath.substringBeforeLast('/')
        candidates.add("$testDir/${mavenStem}Test.$mavenExt")
        candidates.add("$testDir/${mavenStem}Spec.$mavenExt")
    }

    // Kotlin / Java - flat src layout
    val stem = fileName.substringBeforeLast('.')
    val ext = fileName.substringAfterLast('.', "")
    if (dir.isNotEmpty()) {
        candidates.add("$dir/${stem}Test.$ext")
        candidates.add("test/$dir/${stem}Test.$ext")
        candidates.add("tests/$dir/${stem}Test.$ext")
    } else {
        candidates.add("${stem}Test.$ext")
    }

    // Python
    if (ext == "py" && !fileName.startsWith("test_")) {
        candidates.add(if (dir.isNotEmpty()) "$dir/test_$fileName" else "test_$fileName")
        candidates.add(if (dir.isNotEmpty()) "tests/$dir/test_$fileName" else "tests/test_$fileName")
    }

    // JavaScript / TypeScript
    if (ext in setOf("js", "ts", "jsx", "tsx")) {
        val base = if (dir.isNotEmpty()) "$dir/$stem" else stem
        candidates.add("$base.test.$ext")
        candidates.add("$base.spec.$ext")
        candidates.add("__tests__/$stem.test.$ext")
    }

    return candidates.distinct()
}
