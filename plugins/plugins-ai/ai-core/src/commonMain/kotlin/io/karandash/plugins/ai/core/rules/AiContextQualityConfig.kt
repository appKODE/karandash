@file:OptIn(ExperimentalSerializationApi::class)

package io.karandash.plugins.ai.core.rules

import io.karandash.utils.GlobMatcher
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

/**
 * Configuration for AI context quality features (smart truncation, related files,
 * full file context, convention detection).
 *
 * Nested under the existing `ai:` block and parsed alongside [AiRuleConfig]. All
 * fields have defaults that preserve historical behaviour - users opt in to the
 * new features by setting [truncation.strategy] to [TruncationStrategy.PRIORITY]
 * (or by enabling the per-provider flags).
 */
@Serializable
data class AiContextQualityConfig(
    val enabled: Boolean = true,
    val truncation: TruncationConfig = TruncationConfig(),
    @SerialName("related-files") @JsonNames("relatedFiles") val relatedFiles: RelatedFilesConfig = RelatedFilesConfig(),
    @SerialName("full-file-context") @JsonNames("fullFileContext") val fullFileContext: FullFileContextConfig = FullFileContextConfig(),
    val conventions: ConventionsConfig = ConventionsConfig(),
    @SerialName("finding-validator") @JsonNames("findingValidator") val findingValidator: FindingValidatorConfig = FindingValidatorConfig(),
    /** at most this many files, the highest [FilePriorityScorer] scores, go into the diff; null sends all. */
    @SerialName("max-files-to-analyze") @JsonNames("maxFilesToAnalyze") val maxFilesToAnalyze: Int? = null,
    @SerialName("rule-context") @JsonNames("ruleContext") val ruleContext: Map<String, PerRuleContextConfig> = emptyMap(),
    @SerialName("knowledge-context") @JsonNames("knowledgeContext") val knowledgeContext: KnowledgeContextConfig = KnowledgeContextConfig(),
    @SerialName("hunk-expansion") @JsonNames("hunkExpansion") val hunkExpansion: HunkExpansionConfig = HunkExpansionConfig(),
    @SerialName("diff-annotations") @JsonNames("diffAnnotations") val diffAnnotations: DiffAnnotationsConfig = DiffAnnotationsConfig(),
    @SerialName("symbol-definitions") @JsonNames("symbolDefinitions")
    val symbolDefinitions: SymbolDefinitionsConfig = SymbolDefinitionsConfig(),
) {
    init {
        require(maxFilesToAnalyze == null || maxFilesToAnalyze in 1..MAX_FILES_CAP) {
            "max-files-to-analyze must be in 1..$MAX_FILES_CAP, got: $maxFilesToAnalyze"
        }
    }

    companion object {
        /** A suggested cap; unset `max-files-to-analyze` sends every file, as the old pipeline did. */
        const val DEFAULT_MAX_FILES: Int = 100
        const val MAX_FILES_CAP: Int = 500
    }
}

/** Selects the diff truncation algorithm. */
@Serializable
enum class TruncationStrategy {
    /** Existing behaviour - sequential per-file char budget enforced at newline boundary. */
    @SerialName("sequential")
    SEQUENTIAL,

    /**
     * Priority-based: [FilePriorityScorer] ranks files, [TokenBudget.splitAcrossFiles] divides
     * the total budget proportionally, [HunkAwareTruncator] cuts at hunk boundaries.
     */
    @SerialName("priority")
    PRIORITY,
}

/** Token estimator used when reporting budget figures to the user (the truncation decisions). */
@Serializable
enum class TokenEstimator(
    /** Characters counted as one token. */
    val charsPerToken: Int,
) {
    /** Rough 1 token ≈ 4 chars (English / code average). */
    @SerialName("chars4")
    CHARS4(4),

    /** 1 token ≈ 3 chars (denser code, CJK). */
    @SerialName("chars3")
    CHARS3(3),
}

/** How the [TokenBudget.splitAcrossFiles] divides the total budget across scored files. */
@Serializable
enum class BudgetAllocation {
    @SerialName("proportional")
    PROPORTIONAL,

    @SerialName("equal")
    EQUAL,
}

/** @see AiContextQualityConfig.truncation */
@Serializable
data class TruncationConfig(
    val strategy: TruncationStrategy = TruncationStrategy.SEQUENTIAL,
    @SerialName("token-estimator") @JsonNames("tokenEstimator") val tokenEstimator: TokenEstimator = TokenEstimator.CHARS4,
    @SerialName("preserve-hunk-boundaries") @JsonNames("preserveHunkBoundaries") val preserveHunkBoundaries: Boolean = true,
    val priority: TruncationPriorityConfig = TruncationPriorityConfig(),
    @SerialName("budget-allocation") @JsonNames("budgetAllocation") val budgetAllocation: BudgetAllocation = BudgetAllocation.PROPORTIONAL,
)

/** Weights used by [FilePriorityScorer]. Must sum to ~1.0. */
@Serializable
data class TruncationPriorityConfig(
    @SerialName("change-volume-weight") @JsonNames("changeVolumeWeight") val changeVolumeWeight: Double = 0.3,
    @SerialName("file-type-weight") @JsonNames("fileTypeWeight") val fileTypeWeight: Double = 0.3,
    @SerialName("hotspot-weight") @JsonNames("hotspotWeight") val hotspotWeight: Double = 0.2,
    @SerialName("path-pattern-weight") @JsonNames("pathPatternWeight") val pathPatternWeight: Double = 0.2,
    @SerialName("file-type-tiers") @JsonNames("fileTypeTiers") val fileTypeTiers: FileTypeTiers = FileTypeTiers(),
) {
    init {
        val total = changeVolumeWeight + fileTypeWeight + hotspotWeight + pathPatternWeight
        require(total in TOTAL_LOW..TOTAL_HIGH) {
            "priority weights must sum to ~1.0 (got $total) for change/file-type/hotspot/path-pattern"
        }
        require(changeVolumeWeight >= 0.0) { "change-volume-weight must be >= 0, got $changeVolumeWeight" }
        require(fileTypeWeight >= 0.0) { "file-type-weight must be >= 0, got $fileTypeWeight" }
        require(hotspotWeight >= 0.0) { "hotspot-weight must be >= 0, got $hotspotWeight" }
        require(pathPatternWeight >= 0.0) { "path-pattern-weight must be >= 0, got $pathPatternWeight" }
    }

    companion object {
        private const val TOTAL_LOW = 0.99
        private const val TOTAL_HIGH = 1.01
    }
}

/**
 * File-type tiers used by [FilePriorityScorer]. Pattern matching is delegated to
 * [GlobMatcher.Default.matchPath] so behaviour is consistent with CODEOWNERS and
 * other pattern-driven config.
 */
@Serializable
data class FileTypeTiers(
    val high: List<String> = DEFAULT_HIGH,
    val medium: List<String> = DEFAULT_MEDIUM,
    val low: List<String> = DEFAULT_LOW,
) {
    /** @return the tier whose globs match [path], or [FileTier.MEDIUM] for unknown types. */
    fun tierFor(path: String): FileTier {
        val matcher = GlobMatcher.Default
        val fileName = path.substringAfterLast('/')
        // Explicit ordering: HIGH > LOW > MEDIUM. LOW is checked before MEDIUM because
        // `*.generated.kt` is both HIGH-family (.kt) and LOW-family (*.generated.*).
        // Low wins when a source extension is generated.
        return when {
            high.any { matcher.matchPath(fileName, it) || matcher.matchPath(path, it) } -> FileTier.HIGH
            low.any { matcher.matchPath(fileName, it) || matcher.matchPath(path, it) } -> FileTier.LOW
            medium.any { matcher.matchPath(fileName, it) || matcher.matchPath(path, it) } -> FileTier.MEDIUM
            else -> FileTier.MEDIUM
        }
    }

    companion object {
        val DEFAULT_HIGH: List<String> = listOf(
            "*.kt",
            "*.java",
            "*.ts",
            "*.tsx",
            "*.py",
            "*.go",
            "*.rs",
            "*.swift",
            "*.c",
            "*.cpp",
            "*.cc",
            "*.h",
            "*.hpp",
            "*.cs",
            "*.rb",
            "*.scala",
        )
        val DEFAULT_MEDIUM: List<String> = listOf(
            "*.gradle.kts",
            "*.gradle",
            "*.yaml",
            "*.yml",
            "*.toml",
            "*.json",
            "*.xml",
            "*.properties",
        )
        val DEFAULT_LOW: List<String> = listOf(
            "*.md",
            "*.txt",
            "*.csv",
            "*.lock",
            "*.generated.*",
            "*.min.js",
            "*.min.css",
            "*.sum",
        )
    }
}

/** Tier weighting used by [FilePriorityScorer]. */
enum class FileTier(
    val score: Double
) {
    HIGH(1.0),
    MEDIUM(0.5),
    LOW(0.1)
}

/**
 * Related-file providers (tests / callers / interfaces). Off by default; `RelatedFileContextEnricher`
 * reads the rule's resolved [enabled].
 */
@Serializable
data class RelatedFilesConfig(
    val enabled: Boolean = false,
    @SerialName("max-chars") @JsonNames("maxChars") val maxChars: Int = DEFAULT_MAX_CHARS,
    val sources: RelatedFileSources = RelatedFileSources(),
    val depth: Int = 1,
) {
    init {
        require(maxChars in 0..MAX_CHARS_CAP) {
            "related-files.max-chars must be in 0..$MAX_CHARS_CAP, got: $maxChars"
        }
        require(depth in 1..DEPTH_CAP) {
            "related-files.depth must be in 1..$DEPTH_CAP, got: $depth"
        }
    }

    companion object {
        const val DEFAULT_MAX_CHARS: Int = 5_000
        const val MAX_CHARS_CAP: Int = 50_000
        const val DEPTH_CAP: Int = 3
    }
}

/**
 * Flags for [RelatedFilesConfig]. [callers] adds call sites of the changed or removed symbols from a text search of the
 * working tree (off by default: it reads the checkout and adds tokens). [tests] and [interfaces] are not read yet.
 */
@Serializable
data class RelatedFileSources(
    val tests: Boolean = true,
    val callers: Boolean = false,
    val interfaces: Boolean = true,
)

/**
 * `hunk-expansion` ([io.karandash.plugins.ai.core.HunkExpander]): a file of at most [wholeFileMaxLines] lines goes into
 * the diff whole, a bigger one gets each hunk widened to its enclosing block, at most [maxHunkLines] lines. Off by
 * default (more diff tokens); turning it on fetches the head content of up to 100 files.
 */
@Serializable
data class HunkExpansionConfig(
    val enabled: Boolean = false,
    @SerialName("whole-file-max-lines") @JsonNames("wholeFileMaxLines") val wholeFileMaxLines: Int = DEFAULT_WHOLE_FILE_MAX_LINES,
    @SerialName("max-hunk-lines") @JsonNames("maxHunkLines") val maxHunkLines: Int = DEFAULT_MAX_HUNK_LINES,
) {
    init {
        require(wholeFileMaxLines >= 0) { "hunk-expansion.whole-file-max-lines must be >= 0, got: $wholeFileMaxLines" }
        require(maxHunkLines >= 0) { "hunk-expansion.max-hunk-lines must be >= 0, got: $maxHunkLines" }
    }

    companion object {
        const val DEFAULT_WHOLE_FILE_MAX_LINES: Int = 400
        const val DEFAULT_MAX_HUNK_LINES: Int = 150
    }
}

/** `diff-annotations`: deterministic removed-guard and moved-statement notes (`DiffAnnotationsEnricher`); on by default. */
@Serializable
data class DiffAnnotationsConfig(
    val enabled: Boolean = true,
)

/**
 * `symbol-definitions`: definitions of identifiers on changed lines, read from the head content of the changed files
 * (`SymbolDefinitionsEnricher`); opt-in, since enabling it fetches head content.
 */
@Serializable
data class SymbolDefinitionsConfig(
    val enabled: Boolean = false,
)

/** Full-file-context provider. */
@Serializable
data class FullFileContextConfig(
    val enabled: Boolean = false,
    @SerialName("max-file-lines") @JsonNames("maxFileLines") val maxFileLines: Int = DEFAULT_MAX_FILE_LINES,
    @SerialName("max-files") @JsonNames("maxFiles") val maxFiles: Int = DEFAULT_MAX_FILES,
    val mode: FullFileMode = FullFileMode.TARGET,
    val layout: FullFileLayout = FullFileLayout.WINDOWED,
) {
    init {
        require(maxFileLines in 1..MAX_FILE_LINES_CAP) {
            "full-file-context.max-file-lines must be in 1..$MAX_FILE_LINES_CAP, got: $maxFileLines"
        }
        require(maxFiles in 1..MAX_FILES_CAP) {
            "full-file-context.max-files must be in 1..$MAX_FILES_CAP, got: $maxFiles"
        }
    }

    companion object {
        const val DEFAULT_MAX_FILE_LINES: Int = 500
        const val MAX_FILE_LINES_CAP: Int = 2_000
        const val DEFAULT_MAX_FILES: Int = 5
        const val MAX_FILES_CAP: Int = 20
    }
}

/** @see FullFileContextConfig.layout */
@Serializable
enum class FullFileLayout {
    /** Windows around the changed hunks (enclosing block, numbered, `...` gaps) within `max-file-lines` per file. */
    @SerialName("windowed")
    WINDOWED,

    /** The previous behaviour: the complete file, and a file over `max-file-lines` is left out. */
    @SerialName("whole")
    WHOLE,
}

/** @see FullFileContextConfig.mode */
@Serializable
enum class FullFileMode {
    /** Send the new version of the file only. */
    @SerialName("target")
    TARGET,

    /** Send the old and new versions with markers. */
    @SerialName("both")
    BOTH,

    /** Do not inject full files (provider remains disabled). */
    @SerialName("diff-only")
    DIFF_ONLY,
}

/** Convention auto-detection. */
@Serializable
data class ConventionsConfig(
    @SerialName("auto-detect") @JsonNames("autoDetect") val autoDetect: Boolean = true,
    val files: List<ConventionFileRef> = emptyList(),
    @SerialName("max-chars") @JsonNames("maxChars") val maxChars: Int = DEFAULT_MAX_CHARS,
) {
    init {
        require(maxChars in 0..MAX_CHARS_CAP) {
            "conventions.max-chars must be in 0..$MAX_CHARS_CAP, got: $maxChars"
        }
    }

    companion object {
        const val DEFAULT_MAX_CHARS: Int = 3_000
        const val MAX_CHARS_CAP: Int = 20_000
    }
}

/** Entry in [ConventionsConfig.files]. */
@Serializable
data class ConventionFileRef(
    val file: String,
)

/** Flag bundle for the post-dispatch [io.karandash.plugins.ai.core.FindingLocationValidator]. */
@Serializable
data class FindingValidatorConfig(
    val enabled: Boolean = true,
    /**
     * When true (default), findings that reference files absent from the MR changes
     * are dropped. Disable via `strict: false` to treat missing paths as warnings only.
     */
    val strict: Boolean = true,
)

/**
 * Per-rule overrides for any context-quality setting. Every field is nullable -
 * `null` means "inherit from the global [AiContextQualityConfig]".
 */
@Serializable
data class PerRuleContextConfig(
    val truncation: TruncationConfig? = null,
    @SerialName("related-files") @JsonNames("relatedFiles") val relatedFiles: RelatedFilesConfig? = null,
    @SerialName("full-file-context") @JsonNames("fullFileContext") val fullFileContext: FullFileContextConfig? = null,
    val conventions: ConventionsConfig? = null,
    @SerialName("finding-validator") @JsonNames("findingValidator") val findingValidator: FindingValidatorConfig? = null,
    @SerialName("knowledge-context") @JsonNames("knowledgeContext") val knowledgeContext: KnowledgeContextConfig? = null,
)
