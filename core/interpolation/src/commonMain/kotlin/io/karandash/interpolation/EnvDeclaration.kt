package io.karandash.interpolation

import kotlinx.serialization.Serializable

/**
 * Declares custom environment variables allowed for interpolation in config values.
 *
 * Supports two config forms:
 * ```yaml
 * # List form (no defaults)
 * env:
 *   - TASK_ID
 *   - JIRA_PROJECT_KEY
 *
 * # Map form (with optional defaults)
 * env:
 *   TASK_ID: null           # required, no default
 *   JIRA_PROJECT_KEY: "PROJ" # falls back to "PROJ" if not set
 * ```
 *
 * @property vars Map of env var name to default value (null means no default).
 */
@Serializable
data class EnvDeclaration(
    val vars: Map<String, String?>,
) {
    /** All declared env var names. */
    val names: Set<String> get() = vars.keys

    /** Default values for env vars that declare one (non-null entries). */
    val defaults: Map<String, String>
        get() = vars.entries
            .mapNotNull { (name, default) -> default?.let { name to it } }
            .toMap()

    companion object {
        val EMPTY = EnvDeclaration(emptyMap())

        /**
         * Union multiple [EnvDeclaration]s from an extends chain.
         *
         * All names are merged. For defaults, the first non-null default wins
         * (deepest ancestor defines base defaults, children can override by re-declaring).
         */
        fun union(declarations: List<EnvDeclaration>): EnvDeclaration {
            if (declarations.isEmpty()) return EMPTY
            val merged = mutableMapOf<String, String?>()
            for (decl in declarations) {
                for ((name, default) in decl.vars) {
                    if (name !in merged) {
                        merged[name] = default
                    } else if (default != null && merged[name] == null) {
                        merged[name] = default
                    }
                }
            }
            return EnvDeclaration(merged)
        }

        /**
         * Validate that no restricted system env vars are declared.
         *
         * @throws IllegalArgumentException if restricted vars are found.
         */
        fun validate(
            declaration: EnvDeclaration,
            denylist: Set<String> = KarandashEnvSecurity.DENYLIST,
        ) {
            val denied = declaration.names.filter { it in denylist }
            require(denied.isEmpty()) {
                "Restricted environment variable(s) in 'env:' block: " +
                    "${denied.joinToString()}. These system variables cannot be declared in config."
            }
        }
    }
}
