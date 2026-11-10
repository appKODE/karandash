package io.karandash.bootstrap

import io.karandash.api.Module
import io.karandash.normalize.ModuleIdNormalizer
import kotlinx.serialization.Serializable

/**
 * Shared selection logic for module resolution by hint (platform/id) or exact key (formatId).
 */
object ModuleSelector {
    private const val MSG_USE_PLUGINS_ENABLED = "Use config.plugins.enabled to select one."

    @Serializable
    data class SelectionResult<T : Module>(
        val module: T? = null,
        val error: String? = null
    )

    /** Matching form of a module key; delegates to [ModuleIdNormalizer.normalize], the one normalizer. */
    fun normalizeKey(value: String): String = ModuleIdNormalizer.normalize(value)

    fun <T : Module> selectByHint(
        modules: List<T>,
        hint: String?,
        preferredIds: Set<String>,
        typeName: String,
        allowFuzzy: Boolean = true
    ): SelectionResult<T> {
        if (modules.isEmpty()) return SelectionResult()

        val preferredModules = if (preferredIds.isEmpty()) {
            emptyList()
        } else {
            modules.filter { it.id in preferredIds }
        }
        val candidates = preferredModules.ifEmpty { modules }

        val normalizedHint = normalizeKey(hint.orEmpty())
        if (normalizedHint.isNotEmpty()) {
            val exactMatches = candidates.filter { normalizeKey(it.id) == normalizedHint }
            when {
                exactMatches.size == 1 -> {
                    return SelectionResult(module = exactMatches.first())
                }

                exactMatches.size > 1 -> {
                    return SelectionResult(
                        error = "Multiple $typeName match '$hint': ${exactMatches.joinToString { it.id }}"
                    )
                }
            }

            if (allowFuzzy) {
                val fuzzyMatches = candidates.filter {
                    normalizeKey(it.id).contains(normalizedHint) ||
                        normalizeKey(it.name).contains(normalizedHint)
                }
                when {
                    fuzzyMatches.size == 1 -> {
                        return SelectionResult(module = fuzzyMatches.first())
                    }

                    fuzzyMatches.size > 1 -> {
                        return SelectionResult(
                            error = "Multiple $typeName could match '$hint': ${fuzzyMatches.joinToString { it.id }}. " +
                                MSG_USE_PLUGINS_ENABLED
                        )
                    }
                }
            }

            if (preferredModules.size == 1) {
                return SelectionResult(module = preferredModules.first())
            }

            if (preferredModules.size > 1) {
                return SelectionResult(
                    error = "No $typeName match '$hint' among preferred modules: " +
                        preferredModules.joinToString { it.id } +
                        ". Narrow config.plugins.enabled to one module for this role."
                )
            }

            return SelectionResult()
        }

        if (preferredModules.size == 1) {
            return SelectionResult(module = preferredModules.first())
        }

        return when (candidates.size) {
            0 -> SelectionResult()

            1 -> SelectionResult(module = candidates.first())

            else -> SelectionResult(
                error = "Multiple $typeName are available: ${candidates.joinToString { it.id }}. " +
                    MSG_USE_PLUGINS_ENABLED
            )
        }
    }

    fun <T : Module> selectByExactKey(
        modules: List<T>,
        key: String,
        preferredIds: Set<String>,
        typeName: String,
        keyName: String,
        keySelector: (T) -> String
    ): SelectionResult<T> {
        val normalizedKey = normalizeKey(key)
        if (normalizedKey.isEmpty()) return SelectionResult()

        val matching = modules.filter { normalizeKey(keySelector(it)) == normalizedKey }
        if (matching.isEmpty()) return SelectionResult()

        val preferredMatches = if (preferredIds.isEmpty()) {
            emptyList()
        } else {
            matching.filter { it.id in preferredIds }
        }

        if (preferredMatches.size == 1) return SelectionResult(module = preferredMatches.first())
        if (preferredMatches.size > 1) {
            return SelectionResult(
                error = "Multiple preferred $typeName match $keyName '$key': ${preferredMatches.joinToString { it.id }}"
            )
        }

        if (matching.size == 1) return SelectionResult(module = matching.first())

        return SelectionResult(
            error = "Multiple $typeName match $keyName '$key': ${matching.joinToString { it.id }}. " +
                MSG_USE_PLUGINS_ENABLED
        )
    }
}
