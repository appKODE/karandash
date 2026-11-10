package io.karandash.conditions

import io.karandash.api.support.rethrowCancellation
import io.karandash.config.KarandashLimits
import io.karandash.config.PolicyConfig
import io.karandash.config.keys.PolicyKeys
import io.karandash.logger.Logger
import io.karandash.logger.NoOpLogger
import io.karandash.models.ActivePolicy
import kotlin.jvm.JvmOverloads

/**
 * Default implementation of [PolicyEngine].
 *
 * Evaluates each policy's `when` expression using a fresh [ExpressionEvaluator]
 * built from the given predicates and context. On evaluation failure the policy
 * is treated as non-matching and a warning is logged.
 *
 * @param logger Logger for diagnostic messages.
 * @param maxExpressionLength `limits.expressions.maxLength` (effective value).
 * @param maxNestingDepth `limits.expressions.maxNestingDepth` (effective value).
 * @param maxRegexLength `limits.regex.maxPatternLength` (effective value), the cap on a `~=` regex.
 */
class DefaultPolicyEngine
    @JvmOverloads
    constructor(
        private val logger: Logger,
        private val patternExpander: ((String) -> String)?,
        private val maxExpressionLength: Int,
        private val maxNestingDepth: Int,
        private val maxRegexLength: Int = KarandashLimits.MAX_REGEX_LENGTH,
    ) : PolicyEngine {
        /** Built-in expression limits; kept as its own constructor so existing binary signatures stay. */
        @JvmOverloads
        constructor(
            logger: Logger = NoOpLogger,
            patternExpander: ((String) -> String)? = null,
        ) : this(logger, patternExpander, KarandashLimits.MAX_EXPRESSION_LENGTH, KarandashLimits.MAX_NESTING_DEPTH)

        @Suppress("TooGenericExceptionCaught") // boundary: condition expressions may throw on malformed input
        override suspend fun evaluate(
            policies: List<PolicyConfig>,
            context: ConditionContext,
            predicates: PredicateRegistry<ConditionContext>,
        ): List<ActivePolicy> {
            if (policies.isEmpty()) return emptyList()

            // built-ins first, then the caller's predicates: a module predicate wins over a built-in
            val registry = BuiltinPredicates.createRegistry()
            predicates.all().forEach { registry.register(it) }
            val evaluator = BuiltinPredicates.evaluator(
                context,
                registry,
                patternExpander,
                maxExpressionLength,
                maxNestingDepth,
                maxRegexLength
            )

            return policies.map { policy ->
                val matched = try {
                    evaluator.evaluate(policy.condition)
                } catch (e: Exception) {
                    e.rethrowCancellation()
                    // boundary: condition evaluation may throw on malformed expressions
                    logger.warn("Policy condition evaluation failed for '${policy.condition}': ${e.message}")
                    false
                }
                logger.debug("[policy] evaluate ${policy.name}: ${if (matched) "matched" else "not matched"} (modules=${policy.modules})")

                ActivePolicy(
                    name = policy.name,
                    condition = policy.condition,
                    matched = matched,
                    affectedModules = policy.modules,
                    forcedEnabled = if (matched) policy.enabled else null,
                    forcedSeverity = if (matched) policy.severity else null,
                    configOverrides = policy.override,
                    stageOverrides = if (matched) buildStageOverrides(policy) else emptyMap(),
                )
            }
        }

        companion object {
            private fun buildStageOverrides(policy: PolicyConfig): Map<String, Boolean> {
                val map = mutableMapOf<String, Boolean>()
                policy.autoApprove?.let { map[PolicyKeys.AUTO_APPROVE] = it }
                policy.autoFix?.let { map[PolicyKeys.AUTO_FIX] = it }
                policy.scripts?.let { map[PolicyKeys.SCRIPTS] = it }
                policy.dangerfile?.let { map[PolicyKeys.DANGERFILE] = it }
                return map
            }
        }
    }
