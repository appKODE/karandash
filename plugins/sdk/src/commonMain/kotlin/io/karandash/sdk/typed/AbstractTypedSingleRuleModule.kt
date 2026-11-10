package io.karandash.sdk.typed

import io.karandash.api.ExperimentalKarandashApi
import io.karandash.api.run.RunScopedServices
import io.karandash.api.run.RunServiceKeys
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.models.support.PatternAwareCompiler
import io.karandash.sdk.PluginContext
import kotlinx.serialization.KSerializer

/** Hoisted so the [AbstractTypedSingleRuleModule.id] getter does not compile a regex per read. */
private val UPPERCASE_CHAR_REGEX = Regex("([A-Z])")

/**
 * Abstract base class for single-rule modules that use typed config [C].
 *
 * Subclasses declare:
 * - [ruleId]: the single rule ID contributed by this module
 * - [explanation]: human-readable rule description
 * - [doValidateTyped]: the validation logic
 *
 * Usage:
 * ```kotlin
 * class TitleFormatModule : AbstractTypedSingleRuleModule<TitleFormatConfig>(
 *     TitleFormatConfig.serializer()
 * ) {
 *     override val ruleId = "title-format"
 *     override val explanation = "Validates MR title format"
 *     override fun doValidateTyped(mr: MergeRequest, config: TitleFormatConfig) = ...
 * }
 * ```
 *
 * @param C The typed config class.
 */
public abstract class AbstractTypedSingleRuleModule<C : Any>(
    override val configSerializer: KSerializer<C>,
) : TypedRuleModule<C> {
    /** The single rule ID contributed by this module. */
    protected abstract val ruleId: String

    /** Human-readable explanation of this rule. */
    protected abstract val explanation: String

    /**
     * Auto-derived plugin ID from [ruleId] by converting camelCase to kebab-case.
     *
     * For example, `ruleId = "titleFormat"` yields `id = "io.karandash.rule.title-format"`.
     * Override explicitly if the derived ID does not match the desired value.
     */
    override val id: String get() = "io.karandash.rule.${ruleId.replace(UPPERCASE_CHAR_REGEX, "-$1").lowercase()}"

    /**
     * Perform the actual validation with the typed config.
     *
     * @param mr The merge request data.
     * @param config The typed config instance.
     * @return List of findings (empty = pass).
     */
    protected abstract fun doValidateTyped(
        mr: MergeRequest,
        config: C
    ): List<Finding>

    /**
     * [doValidateTyped] with the run's pattern compiler ([RunServiceKeys.PATTERN_COMPILER], `null`
     * outside a run). Rules that compile user patterns override this and pass [patterns] to
     * `RegexHelper.tryCompile`, so `@name` resolves to this run's `patterns:` even while another
     * run with a different definition is in flight. Defaults to the two-argument form.
     */
    protected open fun doValidateTyped(
        mr: MergeRequest,
        config: C,
        patterns: PatternAwareCompiler?,
    ): List<Finding> = doValidateTyped(mr, config)

    /** Generated from the config class, so it lists every key and its default; override only for hand-written text. */
    override val hoverDocumentation: String? get() =
        configHoverMarkdown(ruleId, description, configSerializer.descriptor, configDefaults(configSerializer))

    override suspend fun contributedRules(): Set<String> = setOf(ruleId)

    override suspend fun ruleExplanation(ruleId: String): String? = if (ruleId == this.ruleId) explanation else null

    override suspend fun validateTyped(
        ruleId: String,
        mr: MergeRequest,
        config: C
    ): List<Finding> {
        if (ruleId != this.ruleId) return emptyList()
        return doValidateTyped(mr, config, runPatternCompiler())
    }

    @OptIn(ExperimentalKarandashApi::class)
    private suspend fun runPatternCompiler(): PatternAwareCompiler? = RunScopedServices.current()?.get(RunServiceKeys.PATTERN_COMPILER)

    override val version: String = "1.7.0"

    override val defaultPriority: Int = 10

    override val defaultEnabled: Boolean = true

    override suspend fun initialize(context: PluginContext): Boolean = true
}
