package io.karandash.config.pipeline

import io.karandash.api.errors.ConfigException
import io.karandash.api.errors.KarandashError

/**
 * Re-export from [io.karandash.interpolation.EnvDeclaration].
 */
typealias EnvDeclaration = io.karandash.interpolation.EnvDeclaration

/**
 * CLI-specific validation that wraps the generic [EnvDeclaration.validate] with
 * a [ConfigException] for consistent CLI error reporting (one denylist check, not a copy of it).
 *
 * @throws ConfigException with E0021 if restricted vars are found.
 */
fun validateEnvDeclaration(declaration: EnvDeclaration) {
    try {
        EnvDeclaration.validate(declaration)
    } catch (e: IllegalArgumentException) {
        throw ConfigException("${KarandashError.RESTRICTED_ENV_VAR_IN_CONFIG.bracketCode} ${e.message}", e)
    }
}
