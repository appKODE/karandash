package io.karandash.conditions

import io.karandash.api.context.ConditionContext
import io.karandash.helpers.RegexHelper

/**
 * Environment and CI predicates for condition expressions.
 *
 * Includes bare predicates for CI environment detection and function
 * predicates for platform checks and environment variable queries.
 */
object EnvironmentPredicates {
    /**
     * Registers all environment predicates into [registry].
     *
     * @param registry The predicate registry to register into.
     * @param regexCacheFn Optional cached regex function for regex-based predicates.
     */
    fun registerAll(
        registry: PredicateRegistry<ConditionContext>,
        regexCacheFn: ((String) -> Regex)? = null,
    ) {
        registry.register(IsCiPredicate)
        registry.register(IsGitLabCiPredicate)
        registry.register(IsGitHubActionsPredicate)
        registry.register(CiPlatformPredicate)
        registry.register(EnvSetPredicate)
        registry.register(EnvEqualsPredicate)
        registry.register(EnvMatchesPredicate(regexCacheFn))
    }
}

// ── Bare predicates ─────────────────────────────────────────────────────

private object IsCiPredicate : ConditionPredicate<ConditionContext> {
    override val name: String = "is_ci"
    override val arity: Int = 1
    override val isBarePredicate: Boolean = true

    override fun evaluate(
        args: List<String>,
        context: ConditionContext
    ): Boolean = context.isCI
}

private object IsGitLabCiPredicate : ConditionPredicate<ConditionContext> {
    override val name: String = "is_gitlab_ci"
    override val arity: Int = 1
    override val isBarePredicate: Boolean = true

    override fun evaluate(
        args: List<String>,
        context: ConditionContext
    ): Boolean = context.ciPlatform?.equals("gitlab", ignoreCase = true) == true
}

private object IsGitHubActionsPredicate : ConditionPredicate<ConditionContext> {
    override val name: String = "is_github_actions"
    override val arity: Int = 1
    override val isBarePredicate: Boolean = true

    override fun evaluate(
        args: List<String>,
        context: ConditionContext
    ): Boolean = context.ciPlatform?.equals("github", ignoreCase = true) == true
}

// ── Function predicates ─────────────────────────────────────────────────

private object CiPlatformPredicate : ConditionPredicate<ConditionContext> {
    override val name: String = "ci_platform"
    override val arity: Int = 1

    override fun evaluate(
        args: List<String>,
        context: ConditionContext
    ): Boolean {
        val name = args.getOrNull(0) ?: return false
        return context.ciPlatform?.equals(name, ignoreCase = true) == true
    }
}

private object EnvSetPredicate : ConditionPredicate<ConditionContext> {
    override val name: String = "env_set"
    override val arity: Int = 1

    override fun evaluate(
        args: List<String>,
        context: ConditionContext
    ): Boolean {
        val varName = args.getOrNull(0) ?: return false
        return context.env(varName) != null
    }
}

private object EnvEqualsPredicate : ConditionPredicate<ConditionContext> {
    override val name: String = "env_equals"
    override val arity: Int = 2

    override fun evaluate(
        args: List<String>,
        context: ConditionContext
    ): Boolean {
        val varName = args.getOrNull(0) ?: return false
        val expected = args.getOrNull(1) ?: return false
        return context.env(varName) == expected
    }
}

// ── Regex predicates ────────────────────────────────────────────────────

private class EnvMatchesPredicate(
    private val regexCacheFn: ((String) -> Regex)?,
) : ConditionPredicate<ConditionContext> {
    override val name: String = "env_matches"
    override val arity: Int = 2
    override val isRegexPredicate: Boolean = true

    override fun evaluate(
        args: List<String>,
        context: ConditionContext
    ): Boolean {
        val varName = args.getOrNull(0) ?: return false
        val pattern = args.getOrNull(1) ?: return false
        val value = context.env(varName) ?: return false
        val regex = if (regexCacheFn != null) regexCacheFn.invoke(pattern) else (RegexHelper.compileOrNull(pattern) ?: return false)
        return regex.containsMatchIn(value)
    }
}
