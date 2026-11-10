package io.karandash.conditions

import io.karandash.api.ModuleRegistry
import io.karandash.api.ModuleType
import io.karandash.cache.CacheManager
import io.karandash.cache.CacheTier
import io.karandash.cache.TieredCache
import io.karandash.config.KarandashLimits
import io.karandash.helpers.RegexHelper
import io.karandash.models.finding.Finding
import kotlinx.atomicfu.atomic
import kotlin.jvm.JvmOverloads

/**
 * The one place that decides which predicates every `when` expression sees: the common predicates and
 * the MR field predicates (`branch`, `title`, ...) with their operator syntax. Callers register module-contributed
 * predicates into [createRegistry]'s result afterwards, so a module predicate wins over a built-in of the same name.
 */
object BuiltinPredicates {
    private val fieldExtractors: Map<String, (ConditionContext) -> String> = linkedMapOf(
        "branch" to { it.mr.sourceBranch },
        "title" to { it.mr.title },
        "author" to { it.mr.author.username },
        "description" to { it.mr.description ?: "" },
        "target_branch" to { it.mr.targetBranch },
    )

    private const val LOCAL_REGEX_CACHE_ENTRIES = 256

    private val regexCache = atomic(
        TieredCache<String, Regex>(
            name = ExpressionEvaluator.CACHE_NAME,
            maxEntries = LOCAL_REGEX_CACHE_ENTRIES,
            tier = CacheTier.MEMORY_ONLY,
        ),
    )

    /**
     * Compiles condition regexes once into [cacheManager]'s `condition-regex` cache, so the regex
     * predicates stop recompiling on every evaluation. Called once per run when the cache manager is built.
     */
    fun useManaged(cacheManager: CacheManager) {
        regexCache.value = ExpressionEvaluator.createManagedRegexCache(cacheManager)
    }

    /** The cached compile every built-in regex predicate goes through; an invalid pattern never matches. */
    private fun cachedRegex(pattern: String): Regex =
        regexCache.value.getOrCompute(pattern) { RegexHelper.compileOrNull(pattern) ?: Regex("(?!)") }

    /** A registry holding the built-in common and field predicates. */
    fun createRegistry(): MutablePredicateRegistry<ConditionContext> {
        val registry = MutablePredicateRegistry<ConditionContext>()
        CommonPredicates.registerAll(registry, ::cachedRegex)
        for ((field, extractor) in fieldExtractors) {
            FieldMatchPredicates
                .forField(field, fieldExtractor = extractor, regexCacheFn = ::cachedRegex)
                .forEach { registry.register(it) }
        }
        return registry
    }

    /**
     * The registry every `when` expression of a run sees: built-ins, then what the registered rule modules
     * contribute (module wins), then the `rule_*` result predicates when [priorFindings] is non-null (a later pass).
     */
    fun createRegistry(
        modules: ModuleRegistry,
        priorFindings: List<Finding>?,
    ): MutablePredicateRegistry<ConditionContext> {
        val registry = createRegistry()
        modules.getByType(ModuleType.RULE).forEach { it.registerPredicates(registry) }
        if (priorFindings != null) RuleResultPredicates.register(registry, priorFindings.groupBy { it.rule })
        return registry
    }

    /**
     * An evaluator over [predicates] with the field operator syntax (`title *= "feat:*"`) registered. [maxRegexLength]
     * is `limits.regex.maxPatternLength` (effective value), the cap on a `~=` regex.
     */
    @JvmOverloads
    fun evaluator(
        context: ConditionContext,
        predicates: PredicateRegistry<ConditionContext>,
        patternExpander: ((String) -> String)?,
        maxExpressionLength: Int,
        maxNestingDepth: Int,
        maxRegexLength: Int = KarandashLimits.MAX_REGEX_LENGTH,
    ): ExpressionEvaluator<ConditionContext> {
        val evaluator = ExpressionEvaluator(
            context,
            sharedRegexCache = regexCache.value,
            patternExpander = patternExpander,
            maxExpressionLength = maxExpressionLength,
            maxNestingDepth = maxNestingDepth,
            maxRegexLength = maxRegexLength,
        )
        evaluator.registerAll(predicates)
        for (field in fieldExtractors.keys) {
            evaluator.registerOperatorField(field, FieldMatchPredicates.operatorNames(field))
        }
        return evaluator
    }
}
