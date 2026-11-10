package io.karandash.patterns

import kotlinx.serialization.Serializable

/**
 * Result of compiling a Pomsky DSL expression to a standard regex string.
 *
 * @param regex The compiled regex string on success, or `null` on failure.
 * @param error Human-readable error message when [regex] is `null`.
 * @param warnings Non-fatal warnings produced during compilation.
 */
@Serializable
data class PomskyResult(
    val regex: String?,
    val error: String?,
    val warnings: List<String> = emptyList(),
)

/**
 * Compiles a Pomsky DSL expression to a standard regex string.
 *
 * Implemented as a `fun interface` to allow platform-specific backends
 * (today the only one is [KmpPomskyCompiler], backed by the `pomsky-kt` KMP library).
 */
fun interface PomskyCompiler {
    /**
     * Compiles [expression] (Pomsky DSL source) and returns a [PomskyResult].
     *
     * @param expression A Pomsky source string, without the `pomsky:` prefix.
     */
    fun compile(expression: String): PomskyResult
}
