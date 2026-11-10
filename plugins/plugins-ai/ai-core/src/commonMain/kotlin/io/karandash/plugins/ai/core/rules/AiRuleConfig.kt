package io.karandash.plugins.ai.core.rules

import io.karandash.models.support.KarandashJson
import io.karandash.plugins.ai.core.ContextBudget
import io.karandash.plugins.ai.core.DiffPreprocessorOptions
import io.karandash.plugins.ai.core.TokenUnit
import io.karandash.plugins.ai.core.config.AiAnalyzerTokensConfig
import io.karandash.plugins.ai.core.config.AiBudgetConfig
import io.karandash.plugins.ai.core.config.ChunkingConfig
import io.karandash.sdk.Logger
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * Policy for skipping AI analysis entirely when an MR exceeds size thresholds.
 *
 * Any single threshold exceeded triggers a skip (OR logic).
 * The thresholds are nullable - `null` means "no limit for this dimension".
 *
 * @param trivialDiffs Skip AI review when every changed file is whitespace-only, a lockfile, generated or
 *   docs (`sizePolicy.skip.trivialDiffs`, default `true`).
 */
@Serializable
data class AiSkipPolicy(
    val maxFiles: Int? = null,
    val maxTotalLines: Int? = null,
    val maxCommits: Int? = null,
    val trivialDiffs: Boolean = true,
) {
    /** Returns `true` when no thresholds are configured. */
    fun isUnconfigured(): Boolean = maxFiles == null && maxTotalLines == null && maxCommits == null
}

/** Strategy for selecting which files to review when partial review is active. */
@Serializable(with = PartialReviewStrategySerializer::class)
enum class PartialReviewStrategy {
    /** Select files with the most additions + deletions. */
    MOST_CHANGED,

    /** Take the first N files in the order they appear. */
    FIRST,

    /** Prioritize files matching [AiPartialReviewPolicy.prioritizeTypes], fill remaining with most-changed. */
    BY_TYPE;

    companion object {
        /** Case-insensitive lookup; defaults to [MOST_CHANGED] for unrecognized values. */
        fun fromString(value: String): PartialReviewStrategy =
            entries.firstOrNull { it.name.equals(value.replace("-", "_"), ignoreCase = true) }
                ?: MOST_CHANGED
    }
}

/**
 * Serializes [PartialReviewStrategy] as a lowercase string, deserializes case-insensitively
 * with kebab-case support. Falls back to [PartialReviewStrategy.MOST_CHANGED] for unknown values.
 */
internal object PartialReviewStrategySerializer : KSerializer<PartialReviewStrategy> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("PartialReviewStrategy", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: PartialReviewStrategy
    ) = encoder.encodeString(value.name.lowercase().replace("_", "-"))

    override fun deserialize(decoder: Decoder): PartialReviewStrategy = PartialReviewStrategy.fromString(decoder.decodeString())
}

/**
 * Policy for excluding noise files from AI analysis.
 *
 * Applied unconditionally (regardless of whether partial review is enabled),
 * so generated/noise files never consume AI tokens. Exclusion is active
 * whenever [patterns] is non-empty. The defaults cover generated code, lock files, vendored dependencies and text
 * assets; binary assets (images, fonts) are dropped by the diff preprocessor's binary check instead. A configured
 * `exclude.patterns` list adds to the defaults rather than replacing them.
 *
 * @param patterns Glob patterns identifying files to exclude.
 */
@Serializable
data class AiExcludePolicy(
    val patterns: List<String> = DEFAULT_PATTERNS,
) {
    companion object {
        val DEFAULT_PATTERNS: List<String> = listOf(
            "**/generated/**",
            // Glob matching is case-sensitive: Xcode and SwiftGen output lives under `Generated/`.
            "**/Generated/**",
            "**/*.generated.*",
            "**/package-lock.json",
            "**/*.lock",
            "**/*.lockfile",
            "**/pnpm-lock.yaml",
            "**/go.sum",
            // Root-level only: a `vendor` package inside sources (com/acme/vendor) is code, not a vendored copy.
            "vendor/**",
            "third_party/**",
            "**/node_modules/**",
            "**/*.min.js",
            "**/*.min.css",
            "**/*.js.map",
            "**/*.svg",
            "**/*.xcassets/**",
        )
    }
}

/**
 * Policy for reviewing a subset of files when the MR is large but below skip thresholds.
 *
 * Presence of the `partial-review:` block in config enables partial review.
 * Omitting the block disables it - no explicit `enabled` flag needed.
 *
 * @param maxFiles Maximum number of files to send to the AI.
 * @param strategy How to select the subset of files.
 * @param prioritizeTypes Glob patterns for file types to prioritize (used with [PartialReviewStrategy.BY_TYPE]).
 */
@Serializable
data class AiPartialReviewPolicy(
    val maxFiles: Int = DEFAULT_PARTIAL_MAX_FILES,
    val strategy: PartialReviewStrategy = PartialReviewStrategy.MOST_CHANGED,
    val prioritizeTypes: List<String> = emptyList(),
) {
    companion object {
        const val DEFAULT_PARTIAL_MAX_FILES = 30
    }
}

/**
 * Runtime configuration for AI rule limits, thresholds, and parameters.
 *
 * Per-analyzer settings are grouped into nested config classes (see [AnalyzerLimits.kt]).
 * Cross-cutting fields (diff limits, filters, policies) remain at the top level.
 *
 * User-configurable values read from the plugin config element during module initialization.
 */
@Serializable
data class AiRuleConfig(
    // --- Cross-cutting ---
    val maxDiffCharsPerFile: Int = DEFAULT_MAX_DIFF_CHARS,
    val maxDiffCharsTotal: Int = DEFAULT_MAX_DIFF_CHARS,
    val responseLanguage: String = DEFAULT_RESPONSE_LANGUAGE,
    val fileFilters: List<String> = emptyList(),
    val excludeFilters: List<String> = emptyList(),
    val ruleFileFilters: Map<String, List<String>> = emptyMap(),
    val ruleExcludeFilters: Map<String, List<String>> = emptyMap(),
    val skipPolicy: AiSkipPolicy = AiSkipPolicy(),
    val excludePolicy: AiExcludePolicy = AiExcludePolicy(),
    val partialReviewPolicy: AiPartialReviewPolicy? = null,
    val diffPreprocessor: DiffPreprocessorOptions = DiffPreprocessorOptions(),
    val maxChunks: Int = DEFAULT_MAX_CHUNKS,
    val chunkOverlapLines: Int = DEFAULT_CHUNK_OVERLAP_LINES,
    /** `ai.analyzer.tokens`: floor, ceiling and chars-per-token estimate of the auto-scaled response tokens. */
    val analyzerTokens: AiAnalyzerTokensConfig = AiAnalyzerTokensConfig(),
    // --- Per-analyzer groups ---
    val riskScoring: RiskScoringLimits = RiskScoringLimits(),
    val categorizer: CategorizerLimits = CategorizerLimits(),
    val review: ReviewLimits = ReviewLimits(),
    val description: DescriptionLimits = DescriptionLimits(),
    val fixSuggestion: FixSuggestionLimits = FixSuggestionLimits(),
    val similarIssue: SimilarIssueLimits = SimilarIssueLimits(),
    val codeSuggestion: CodeSuggestionLimits = CodeSuggestionLimits(),
    val split: SplitLimits = SplitLimits(),
    val codeQuality: CodeQualityLimits = CodeQualityLimits(),
    val ownership: OwnershipLimits = OwnershipLimits(),
    /** `ai.limits.<featureKey>MaxTokens` of the analyzers without a limits group of their own; see [tokensFor]. */
    val featureMaxTokens: Map<String, Int> = emptyMap(),
    // --- Context quality (smart truncation, related files, full-file context,
    //     convention detection, finding location validator). Defaults preserve
    //     historical behaviour - users opt in via `contextQuality.truncation.strategy`.
    val contextQuality: AiContextQualityConfig = AiContextQualityConfig(),
) {
    // --- Flat accessors (delegating to nested groups) ---

    /** @see RiskScoringLimits.maxTokens */
    val riskScorerMaxTokens: Int get() = riskScoring.maxTokens

    /** @see RiskScoringLimits.temperature */
    val riskScorerTemperature: Double get() = riskScoring.temperature

    /** @see RiskScoringLimits.errorThreshold */
    val riskErrorThreshold: Double get() = riskScoring.errorThreshold

    /** @see RiskScoringLimits.errorEnabled */
    val riskErrorEnabled: Boolean get() = riskScoring.errorEnabled

    /** @see RiskScoringLimits.warningThreshold */
    val riskWarningThreshold: Double get() = riskScoring.warningThreshold

    /** @see RiskScoringLimits.includeDiff */
    val riskScoringIncludeDiff: Boolean get() = riskScoring.includeDiff

    /** @see CategorizerLimits.maxTokens */
    val categorizerMaxTokens: Int get() = categorizer.maxTokens

    /** @see CategorizerLimits.temperature */
    val categorizerTemperature: Double get() = categorizer.temperature

    /** @see ReviewLimits.maxTokens */
    val reviewMaxTokens: Int get() = review.maxTokens

    /** @see ReviewLimits.temperature */
    val reviewTemperature: Double get() = review.temperature

    /** @see ReviewLimits.maxComments */
    val maxComments: Int get() = review.maxComments

    /** @see DescriptionLimits.maxTokens */
    val descriptionMaxTokens: Int get() = description.maxTokens

    /** @see DescriptionLimits.temperature */
    val descriptionTemperature: Double get() = description.temperature

    /** @see FixSuggestionLimits.maxTokens */
    val fixSuggesterMaxTokens: Int get() = fixSuggestion.maxTokens

    /** @see FixSuggestionLimits.temperature */
    val fixSuggesterTemperature: Double get() = fixSuggestion.temperature

    /** @see FixSuggestionLimits.includeFixSuggestions */
    val includeFixSuggestions: Boolean get() = fixSuggestion.includeFixSuggestions

    /** @see FixSuggestionLimits.minSeverity */
    val fixSuggestionMinSeverity: String get() = fixSuggestion.minSeverity

    /** @see SimilarIssueLimits.maxTokens */
    val similarIssueMaxTokens: Int get() = similarIssue.maxTokens

    /** @see SimilarIssueLimits.temperature */
    val similarIssueTemperature: Double get() = similarIssue.temperature

    /** @see SimilarIssueLimits.maxDisplayPatterns */
    val similarIssueMaxDisplayPatterns: Int get() = similarIssue.maxDisplayPatterns

    /** @see CodeSuggestionLimits.maxTokens */
    val codeSuggestionMaxTokens: Int get() = codeSuggestion.maxTokens

    /** @see CodeSuggestionLimits.temperature */
    val codeSuggestionTemperature: Double get() = codeSuggestion.temperature

    /** @see CodeSuggestionLimits.mode */
    val codeSuggestionMode: String get() = codeSuggestion.mode

    /** @see SplitLimits.threshold */
    val splitThreshold: Int get() = split.threshold

    /** @see CodeQualityLimits.maxTokens */
    val codeQualityMaxTokens: Int get() = codeQuality.maxTokens

    /** @see CodeQualityLimits.temperature */
    val codeQualityTemperature: Double get() = codeQuality.temperature

    /** @see CodeQualityLimits.warningThreshold */
    val codeQualityWarningThreshold: Double get() = codeQuality.warningThreshold

    /** @see CodeQualityLimits.errorThreshold */
    val codeQualityErrorThreshold: Double get() = codeQuality.errorThreshold

    /** @see CodeQualityLimits.includeMaturityLevel */
    val codeQualityIncludeMaturityLevel: Boolean get() = codeQuality.includeMaturityLevel

    /** @see OwnershipLimits.maxTokens */
    val ownershipMaxTokens: Int get() = ownership.maxTokens

    /** @see OwnershipLimits.temperature */
    val ownershipTemperature: Double get() = ownership.temperature

    /** @see OwnershipLimits.warningThreshold */
    val ownershipWarningThreshold: Double get() = ownership.warningThreshold

    /** @see OwnershipLimits.errorThreshold */
    val ownershipErrorThreshold: Double get() = ownership.errorThreshold

    companion object {
        private const val FILTER_INCLUDE = "include"

        /** `ai.limits` diff caps; `ai.budget.max-input-tokens` derives both. */
        const val KEY_MAX_DIFF_CHARS_TOTAL = "maxDiffCharsTotal"
        const val KEY_MAX_DIFF_CHARS_PER_FILE = "maxDiffCharsPerFile"
        private const val FILTER_EXCLUDE = "exclude"

        /**
         * Both diff caps without `ai.budget.max-input-tokens` or `limits`: the largest `auto` context budget in
         * characters, a safety ceiling that does not bind before the budget does.
         */
        val DEFAULT_MAX_DIFF_CHARS: Int = TokenUnit.tokensToChars(ContextBudget.AUTO_MAX_TOKENS)
        const val DEFAULT_RESPONSE_LANGUAGE = "English"
        const val DEFAULT_MAX_CHUNKS = ChunkingConfig.DEFAULT_MAX_CHUNKS

        /** No hunk overlap unless `ai.limits.chunkOverlapLines` asks for it (the old chunker carried none). */
        const val DEFAULT_CHUNK_OVERLAP_LINES = 0

        // Flat aliases for constants defined in nested companions
        const val DEFAULT_CATEGORIZER_MAX_TOKENS = CategorizerLimits.DEFAULT_MAX_TOKENS
        const val DEFAULT_CATEGORIZER_TEMPERATURE = CategorizerLimits.DEFAULT_TEMPERATURE
        const val DEFAULT_RISK_SCORER_MAX_TOKENS = RiskScoringLimits.DEFAULT_MAX_TOKENS
        const val DEFAULT_RISK_SCORER_TEMPERATURE = RiskScoringLimits.DEFAULT_TEMPERATURE
        const val DEFAULT_FIX_SUGGESTER_MAX_TOKENS = FixSuggestionLimits.DEFAULT_MAX_TOKENS
        const val DEFAULT_FIX_SUGGESTER_TEMPERATURE = FixSuggestionLimits.DEFAULT_TEMPERATURE
        const val DEFAULT_SIMILAR_ISSUE_MAX_TOKENS = SimilarIssueLimits.DEFAULT_MAX_TOKENS
        const val DEFAULT_SIMILAR_ISSUE_TEMPERATURE = SimilarIssueLimits.DEFAULT_TEMPERATURE
        const val DEFAULT_SIMILAR_ISSUE_MAX_DISPLAY_PATTERNS = SimilarIssueLimits.DEFAULT_MAX_DISPLAY_PATTERNS
        const val DEFAULT_REVIEW_MAX_TOKENS = ReviewLimits.DEFAULT_MAX_TOKENS
        const val DEFAULT_REVIEW_TEMPERATURE = ReviewLimits.DEFAULT_TEMPERATURE
        const val DEFAULT_DESCRIPTION_MAX_TOKENS = DescriptionLimits.DEFAULT_MAX_TOKENS
        const val DEFAULT_DESCRIPTION_TEMPERATURE = DescriptionLimits.DEFAULT_TEMPERATURE
        const val DEFAULT_CODE_SUGGESTION_MAX_TOKENS = CodeSuggestionLimits.DEFAULT_MAX_TOKENS
        const val DEFAULT_CODE_SUGGESTION_TEMPERATURE = CodeSuggestionLimits.DEFAULT_TEMPERATURE
        const val DEFAULT_CODE_QUALITY_MAX_TOKENS = CodeQualityLimits.DEFAULT_MAX_TOKENS
        const val DEFAULT_CODE_QUALITY_TEMPERATURE = CodeQualityLimits.DEFAULT_TEMPERATURE
        const val DEFAULT_CODE_QUALITY_WARNING_THRESHOLD = CodeQualityLimits.DEFAULT_WARNING_THRESHOLD
        const val DEFAULT_CODE_QUALITY_ERROR_THRESHOLD = CodeQualityLimits.DEFAULT_ERROR_THRESHOLD
        const val DEFAULT_CODE_QUALITY_INCLUDE_MATURITY_LEVEL = CodeQualityLimits.DEFAULT_INCLUDE_MATURITY_LEVEL
        const val DEFAULT_OWNERSHIP_MAX_TOKENS = OwnershipLimits.DEFAULT_MAX_TOKENS
        const val DEFAULT_OWNERSHIP_TEMPERATURE = OwnershipLimits.DEFAULT_TEMPERATURE
        const val DEFAULT_OWNERSHIP_WARNING_THRESHOLD = OwnershipLimits.DEFAULT_WARNING_THRESHOLD
        const val DEFAULT_OWNERSHIP_ERROR_THRESHOLD = OwnershipLimits.DEFAULT_ERROR_THRESHOLD
        const val DEFAULT_RISK_ERROR_THRESHOLD = RiskScoringLimits.DEFAULT_ERROR_THRESHOLD
        const val DEFAULT_RISK_WARNING_THRESHOLD = RiskScoringLimits.DEFAULT_WARNING_THRESHOLD
        const val DEFAULT_MAX_COMMENTS = ReviewLimits.DEFAULT_MAX_COMMENTS
        const val DEFAULT_SPLIT_THRESHOLD = SplitLimits.DEFAULT_THRESHOLD
        const val DEFAULT_INCLUDE_FIX_SUGGESTIONS = FixSuggestionLimits.DEFAULT_INCLUDE_FIX_SUGGESTIONS
        const val DEFAULT_FIX_SUGGESTION_MIN_SEVERITY = FixSuggestionLimits.DEFAULT_MIN_SEVERITY
        const val DEFAULT_CODE_SUGGESTION_MODE = CodeSuggestionLimits.DEFAULT_MODE
        const val DEFAULT_RISK_SCORING_INCLUDE_DIFF = RiskScoringLimits.DEFAULT_INCLUDE_DIFF

        /**
         * Builds an [AiRuleConfig] from a [JsonElement].
         *
         * Reads `limits`, `thresholds`, `size-policy`, `filters`, `ruleFilters`, and
         * top-level scalar fields. Unrecognized or absent keys fall back to defaults.
         * A malformed string list (e.g. `filters.include` that is not an array) reads as empty and is
         * reported through [logger] when one is given; pass it from the one-time initialization site only.
         */
        fun fromPluginConfig(
            config: JsonElement,
            logger: Logger? = null,
        ): AiRuleConfig {
            val obj = config as? JsonObject ?: return AiRuleConfig()
            val limits = obj["limits"] as? JsonObject ?: JsonObject(emptyMap())
            val thresholds = obj["thresholds"] as? JsonObject ?: JsonObject(emptyMap())
            val sizePolicy = obj["size-policy"] as? JsonObject ?: obj["sizePolicy"] as? JsonObject
            val filters = obj["filters"] as? JsonObject
            val ruleFilters = obj["ruleFilters"] as? JsonObject
            // The diff caps are safety ceilings: an explicit max-input-tokens, else at least the largest auto budget
            // (or an explicit ai.context.budget above it); the tier plan then gives the diff what T0 leaves.
            val budget = parseBudget(obj["budget"])
            val contextBudget = ContextBudget.parse((obj["context"] as? JsonObject)?.get("budget"))
            val budgetChars = TokenUnit.tokensToChars(
                if (budget.maxInputTokens != null) budget.promptCapTokens else maxOf(ContextBudget.AUTO_MAX_TOKENS, contextBudget ?: 0),
            )

            return AiRuleConfig(
                maxDiffCharsPerFile = limits[KEY_MAX_DIFF_CHARS_PER_FILE]?.jsonPrimitive?.intOrNull ?: budgetChars,
                maxDiffCharsTotal = limits[KEY_MAX_DIFF_CHARS_TOTAL]?.jsonPrimitive?.intOrNull ?: budgetChars,
                responseLanguage = obj["responseLanguage"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                    ?: DEFAULT_RESPONSE_LANGUAGE,
                maxChunks = limits["maxChunks"]?.jsonPrimitive?.intOrNull
                    ?: DEFAULT_MAX_CHUNKS,
                chunkOverlapLines = limits["chunkOverlapLines"]?.jsonPrimitive?.intOrNull
                    ?: DEFAULT_CHUNK_OVERLAP_LINES,
                analyzerTokens = parseAnalyzerTokens(obj, logger),
                fileFilters = parseStringList(filters?.get(FILTER_INCLUDE), "filters.$FILTER_INCLUDE", logger),
                excludeFilters = parseStringList(filters?.get(FILTER_EXCLUDE), "filters.$FILTER_EXCLUDE", logger),
                ruleFileFilters = parseRuleFilterMap(ruleFilters, FILTER_INCLUDE, logger),
                ruleExcludeFilters = parseRuleFilterMap(ruleFilters, FILTER_EXCLUDE, logger),
                skipPolicy = parseSkipPolicy(sizePolicy),
                excludePolicy = parseExcludePolicy(sizePolicy, logger),
                partialReviewPolicy = parsePartialReviewPolicy(sizePolicy, logger),
                diffPreprocessor = parseDiffPreprocessor(sizePolicy),
                riskScoring = parseRiskScoringLimits(limits, thresholds, obj),
                categorizer = parseCategorizerLimits(limits),
                review = parseReviewLimits(limits, obj),
                description = parseDescriptionLimits(limits),
                fixSuggestion = parseFixSuggestionLimits(limits, obj),
                similarIssue = parseSimilarIssueLimits(limits),
                codeSuggestion = parseCodeSuggestionLimits(limits, obj),
                split = parseSplitLimits(obj),
                codeQuality = parseCodeQualityLimits(limits, thresholds, obj),
                ownership = parseOwnershipLimits(limits, thresholds),
                featureMaxTokens = parseFeatureMaxTokens(limits, logger),
                contextQuality = parseContextQuality(obj),
            )
        }

        /**
         * Decodes `ai.contextQuality` (or its kebab alias). A type mismatch propagates so the
         * AI module's initialization boundary reports it instead of silently using defaults.
         */
        private fun parseContextQuality(obj: JsonObject): AiContextQualityConfig {
            val block = obj["contextQuality"] ?: obj["context-quality"] ?: return AiContextQualityConfig()
            return KarandashJson.Default.decodeFromJsonElement(AiContextQualityConfig.serializer(), block)
        }

        /** Decodes `ai.budget`; a type mismatch propagates to the initialization boundary like [parseContextQuality]. */
        private fun parseBudget(block: JsonElement?): AiBudgetConfig =
            block?.let { KarandashJson.Default.decodeFromJsonElement(AiBudgetConfig.serializer(), it) } ?: AiBudgetConfig()

        // --- Group parsers ---

        private fun parseAnalyzerTokens(
            obj: JsonObject,
            logger: Logger?,
        ): AiAnalyzerTokensConfig =
            ((obj["analyzer"] as? JsonObject)?.get("tokens"))?.let { AiAnalyzerTokensConfig.parse(it, logger) }
                ?: AiAnalyzerTokensConfig()

        private fun parseRiskScoringLimits(
            limits: JsonObject,
            thresholds: JsonObject,
            obj: JsonObject,
        ): RiskScoringLimits =
            RiskScoringLimits(
                maxTokens = limits["riskScorerMaxTokens"]?.jsonPrimitive?.intOrNull
                    ?: RiskScoringLimits.DEFAULT_MAX_TOKENS,
                temperature = RiskScoringLimits.DEFAULT_TEMPERATURE,
                errorThreshold = thresholds["riskError"]?.jsonPrimitive?.doubleOrNull
                    ?: RiskScoringLimits.DEFAULT_ERROR_THRESHOLD,
                errorEnabled = thresholds["riskError"]?.jsonPrimitive?.doubleOrNull != null,
                warningThreshold = thresholds["riskWarning"]?.jsonPrimitive?.doubleOrNull
                    ?: RiskScoringLimits.DEFAULT_WARNING_THRESHOLD,
                includeDiff = obj["riskScoringIncludeDiff"]?.jsonPrimitive?.booleanOrNull
                    ?: RiskScoringLimits.DEFAULT_INCLUDE_DIFF,
                maxDiffChars = limits["riskScoringMaxDiffChars"]?.jsonPrimitive?.intOrNull,
            )

        private fun parseCategorizerLimits(limits: JsonObject): CategorizerLimits =
            CategorizerLimits(
                maxTokens = limits["categorizerMaxTokens"]?.jsonPrimitive?.intOrNull
                    ?: CategorizerLimits.DEFAULT_MAX_TOKENS,
            )

        private fun parseReviewLimits(
            limits: JsonObject,
            obj: JsonObject,
        ): ReviewLimits =
            ReviewLimits(
                maxTokens = limits["reviewMaxTokens"]?.jsonPrimitive?.intOrNull
                    ?: ReviewLimits.DEFAULT_MAX_TOKENS,
                maxComments = obj["maxComments"]?.jsonPrimitive?.intOrNull
                    ?: ReviewLimits.DEFAULT_MAX_COMMENTS,
                maxCommentsHard = obj["maxCommentsHard"]?.jsonPrimitive?.intOrNull?.takeIf { it > 0 },
            )

        private fun parseDescriptionLimits(limits: JsonObject): DescriptionLimits =
            DescriptionLimits(
                maxTokens = limits["descriptionMaxTokens"]?.jsonPrimitive?.intOrNull
                    ?: DescriptionLimits.DEFAULT_MAX_TOKENS,
            )

        private fun parseFixSuggestionLimits(
            limits: JsonObject,
            obj: JsonObject,
        ): FixSuggestionLimits =
            FixSuggestionLimits(
                maxTokens = limits["fixSuggesterMaxTokens"]?.jsonPrimitive?.intOrNull
                    ?: FixSuggestionLimits.DEFAULT_MAX_TOKENS,
                includeFixSuggestions = obj["includeFixSuggestions"]?.jsonPrimitive?.booleanOrNull
                    ?: FixSuggestionLimits.DEFAULT_INCLUDE_FIX_SUGGESTIONS,
                minSeverity = obj["fixSuggestionMinSeverity"]
                    ?.jsonPrimitive
                    ?.content
                    ?.takeIf { it.isNotBlank() } ?: FixSuggestionLimits.DEFAULT_MIN_SEVERITY,
            )

        private fun parseSimilarIssueLimits(limits: JsonObject): SimilarIssueLimits =
            SimilarIssueLimits(
                maxTokens = limits["similarIssueMaxTokens"]?.jsonPrimitive?.intOrNull
                    ?: SimilarIssueLimits.DEFAULT_MAX_TOKENS,
            )

        private fun parseCodeSuggestionLimits(
            limits: JsonObject,
            obj: JsonObject,
        ): CodeSuggestionLimits =
            CodeSuggestionLimits(
                maxTokens = limits["codeSuggestionMaxTokens"]?.jsonPrimitive?.intOrNull
                    ?: CodeSuggestionLimits.DEFAULT_MAX_TOKENS,
                mode = obj["codeSuggestionMode"]
                    ?.jsonPrimitive
                    ?.content
                    ?.takeIf { it.isNotBlank() } ?: CodeSuggestionLimits.DEFAULT_MODE,
            )

        private fun parseSplitLimits(obj: JsonObject): SplitLimits =
            SplitLimits(
                threshold = obj["splitThreshold"]?.jsonPrimitive?.intOrNull
                    ?: SplitLimits.DEFAULT_THRESHOLD,
            )

        private fun parseCodeQualityLimits(
            limits: JsonObject,
            thresholds: JsonObject,
            obj: JsonObject,
        ): CodeQualityLimits =
            CodeQualityLimits(
                maxTokens = limits["codeQualityMaxTokens"]?.jsonPrimitive?.intOrNull
                    ?: CodeQualityLimits.DEFAULT_MAX_TOKENS,
                warningThreshold = thresholds["codeQualityWarning"]?.jsonPrimitive?.doubleOrNull
                    ?: CodeQualityLimits.DEFAULT_WARNING_THRESHOLD,
                errorThreshold = thresholds["codeQualityError"]?.jsonPrimitive?.doubleOrNull
                    ?: CodeQualityLimits.DEFAULT_ERROR_THRESHOLD,
                includeMaturityLevel = obj["codeQualityIncludeMaturityLevel"]
                    ?.jsonPrimitive
                    ?.booleanOrNull
                    ?: CodeQualityLimits.DEFAULT_INCLUDE_MATURITY_LEVEL,
            )

        private fun parseOwnershipLimits(
            limits: JsonObject,
            thresholds: JsonObject,
        ): OwnershipLimits =
            OwnershipLimits(
                maxTokens = limits["ownershipMaxTokens"]?.jsonPrimitive?.intOrNull
                    ?: OwnershipLimits.DEFAULT_MAX_TOKENS,
                warningThreshold = thresholds["ownershipWarning"]?.jsonPrimitive?.doubleOrNull
                    ?: OwnershipLimits.DEFAULT_WARNING_THRESHOLD,
                errorThreshold = thresholds["ownershipError"]?.jsonPrimitive?.doubleOrNull
                    ?: OwnershipLimits.DEFAULT_ERROR_THRESHOLD,
            )

        // --- Utility parsers ---

        private fun parseStringList(
            element: JsonElement?,
            path: String,
            logger: Logger?,
        ): List<String> {
            if (element == null || element is JsonNull) return emptyList()
            return try {
                element.jsonArray.mapNotNull { it.jsonPrimitive.takeIf { p -> p.isString }?.content }
            } catch (_: IllegalArgumentException) {
                logger?.warn("AI config '$path' must be a list of strings; ignoring it")
                emptyList()
            }
        }

        private fun parseRuleFilterMap(
            ruleFilters: JsonObject?,
            key: String,
            logger: Logger?,
        ): Map<String, List<String>> {
            if (ruleFilters == null) return emptyMap()
            return ruleFilters.entries
                .mapNotNull { (ruleId, ruleConfig) ->
                    val cfg = ruleConfig as? JsonObject ?: return@mapNotNull null
                    val list = parseStringList(cfg[key], "ruleFilters.$ruleId.$key", logger)
                    if (list.isEmpty()) null else ruleId to list
                }.toMap()
        }

        private fun parseSkipPolicy(sizePolicy: JsonObject?): AiSkipPolicy {
            val skip = sizePolicy?.get("skip") as? JsonObject ?: return AiSkipPolicy()
            return AiSkipPolicy(
                maxFiles = skip["maxFiles"]?.jsonPrimitive?.intOrNull,
                maxTotalLines = skip["maxTotalLines"]?.jsonPrimitive?.intOrNull,
                maxCommits = skip["maxCommits"]?.jsonPrimitive?.intOrNull,
                trivialDiffs = skip["trivialDiffs"]?.jsonPrimitive?.booleanOrNull ?: true,
            )
        }

        private fun parseExcludePolicy(
            sizePolicy: JsonObject?,
            logger: Logger?,
        ): AiExcludePolicy {
            val exclude = sizePolicy?.get("exclude") as? JsonObject ?: return AiExcludePolicy()
            val patterns = parseStringList(exclude["patterns"], "sizePolicy.exclude.patterns", logger)
            return AiExcludePolicy(patterns = (AiExcludePolicy.DEFAULT_PATTERNS + patterns).distinct())
        }

        private fun parseDiffPreprocessor(sizePolicy: JsonObject?): DiffPreprocessorOptions {
            val block = sizePolicy?.get("diff-preprocessor") as? JsonObject
                ?: sizePolicy?.get("diffPreprocessor") as? JsonObject
                ?: return DiffPreprocessorOptions()
            return DiffPreprocessorOptions(
                skipDeletedFiles = block["skipDeletedFiles"]?.jsonPrimitive?.booleanOrNull
                    ?: DiffPreprocessorOptions.DEFAULT_SKIP_DELETED_FILES,
                skipPureRenames = block["skipPureRenames"]?.jsonPrimitive?.booleanOrNull
                    ?: DiffPreprocessorOptions.DEFAULT_SKIP_PURE_RENAMES,
                stripDeletedLines = block["stripDeletedLines"]?.jsonPrimitive?.booleanOrNull
                    ?: DiffPreprocessorOptions.DEFAULT_STRIP_DELETED_LINES,
                stripDiffMetadata = block["stripDiffMetadata"]?.jsonPrimitive?.booleanOrNull
                    ?: DiffPreprocessorOptions.DEFAULT_STRIP_DIFF_METADATA,
                skipBinaryDiffs = block["skipBinaryDiffs"]?.jsonPrimitive?.booleanOrNull
                    ?: DiffPreprocessorOptions.DEFAULT_SKIP_BINARY_DIFFS,
                deletedFileStubLines = block["deletedFileStubLines"]?.jsonPrimitive?.intOrNull?.coerceAtLeast(0)
                    ?: DiffPreprocessorOptions.DEFAULT_DELETED_FILE_STUB_LINES,
            )
        }

        private fun parsePartialReviewPolicy(
            sizePolicy: JsonObject?,
            logger: Logger?,
        ): AiPartialReviewPolicy? {
            val partial = sizePolicy?.get("partialReview") as? JsonObject
                ?: sizePolicy?.get("partial-review") as? JsonObject
                ?: return null
            return AiPartialReviewPolicy(
                maxFiles = partial["maxFiles"]?.jsonPrimitive?.intOrNull
                    ?: AiPartialReviewPolicy.DEFAULT_PARTIAL_MAX_FILES,
                strategy = PartialReviewStrategy.fromString(
                    partial["strategy"]?.jsonPrimitive?.content ?: "most-changed",
                ),
                prioritizeTypes = parseStringList(
                    partial["prioritizeTypes"] ?: partial["prioritize-types"],
                    "sizePolicy.partialReview.prioritizeTypes",
                    logger,
                ),
            )
        }
    }
}
