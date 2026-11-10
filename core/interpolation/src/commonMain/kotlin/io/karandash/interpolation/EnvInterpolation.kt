package io.karandash.interpolation

import io.karandash.platform.PlatformEnvironment

/**
 * Environment variable interpolation for config strings.
 *
 * Replaces `${VAR_NAME}` tokens with values from the system environment,
 * with support for a security denylist, fallback defaults, and inline defaults
 * via the `${VAR:-default}` syntax.
 */
object EnvInterpolation {
    /** Pattern matching `${VAR_NAME}` and `${VAR_NAME:-default}` tokens in config values. */
    private val INTERPOLATION_REGEX = Regex("""\$\{([^}]+)\}""")

    /** Separator between variable name and inline default value. */
    private const val INLINE_DEFAULT_SEPARATOR = ":-"

    /**
     * Interpolate environment variables in the format `${VAR_NAME}` or `${VAR_NAME:-default}`.
     *
     * **Security**: Env vars in the [denylist] are never interpolated (security-sensitive vars
     * like PATH, HOME, SSH keys). All other vars are resolved from the environment; if unset,
     * the [defaults] map is consulted; if still unset, the inline default from `:-` syntax is
     * used; if still missing, the var name is added to [missingVars] and replaced with an empty
     * string.
     *
     * @param value The string containing `${VAR}` or `${VAR:-default}` tokens to resolve.
     * @param denylist Env var names that must not be interpolated.
     * @param defaults Fallback values when env var is not set (takes priority over inline defaults).
     * @param missingVars Accumulates names of vars not found in env, defaults, or inline defaults.
     * @param deniedVars Accumulates names of vars that were in the denylist and left as placeholders.
     * @return The interpolated string.
     */
    fun interpolate(
        value: String,
        denylist: Set<String> = emptySet(),
        defaults: Map<String, String> = emptyMap(),
        missingVars: MutableSet<String>? = null,
        deniedVars: MutableSet<String>? = null,
        onResolve: ((varName: String, source: String, resolvedValue: String?) -> Unit)? = null,
    ): String =
        INTERPOLATION_REGEX.replace(value) { matchResult ->
            val rawCapture = matchResult.groupValues[1]
            val (varName, inlineDefault) = parseVarCapture(rawCapture)
            when {
                varName in denylist -> {
                    deniedVars?.add(varName)
                    onResolve?.invoke(varName, "denied", null)
                    matchResult.value
                }

                else -> {
                    resolveVar(varName, inlineDefault, defaults, missingVars, onResolve)
                }
            }
        }

    /**
     * Parse the captured group into a variable name and optional inline default.
     * For `VAR:-value`, returns `("VAR", "value")`. For `VAR`, returns `("VAR", null)`.
     */
    private fun parseVarCapture(rawCapture: String): Pair<String, String?> {
        val separatorIndex = rawCapture.indexOf(INLINE_DEFAULT_SEPARATOR)
        return if (separatorIndex >= 0) {
            val varName = rawCapture.substring(0, separatorIndex)
            val inlineDefault = rawCapture.substring(separatorIndex + INLINE_DEFAULT_SEPARATOR.length)
            varName to inlineDefault
        } else {
            rawCapture to null
        }
    }

    /** Pattern matching `$VAR_NAME` without braces - potential authoring error. */
    private val UNBRACED_VAR_REGEX = Regex("""\$([A-Z][A-Z0-9_]{2,})""")

    /**
     * Detects `$VAR_NAME` tokens (without braces) that match known env var names
     * from the [declaredVars] set. Returns a list of variable names that appear
     * to be unbraced references.
     *
     * Only flags variables that are also declared in the `env:` block - random
     * `$` signs in regex patterns or text are ignored.
     */
    fun detectUnbracedReferences(
        value: String,
        declaredVars: Set<String>,
    ): List<String> {
        if ('$' !in value || declaredVars.isEmpty()) return emptyList()
        return UNBRACED_VAR_REGEX
            .findAll(value)
            .map { it.groupValues[1] }
            .filter { it in declaredVars }
            .toList()
    }

    /** Resolve a variable value from environment, defaults map, or inline default. */
    private fun resolveVar(
        varName: String,
        inlineDefault: String?,
        defaults: Map<String, String>,
        missingVars: MutableSet<String>?,
        onResolve: ((String, String, String?) -> Unit)?,
    ): String {
        val envValue = PlatformEnvironment.getEnv(varName)
        if (envValue != null) {
            onResolve?.invoke(varName, "env", envValue)
            return envValue
        }
        val defaultValue = defaults[varName]
        if (defaultValue != null) {
            onResolve?.invoke(varName, "default", defaultValue)
            return defaultValue
        }
        if (inlineDefault != null) {
            onResolve?.invoke(varName, "inline", inlineDefault)
            return inlineDefault
        }
        onResolve?.invoke(varName, "MISSING", null)
        missingVars?.add(varName)
        return ""
    }
}
