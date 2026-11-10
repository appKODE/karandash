package io.karandash.providers.common

import io.karandash.api.PluginContext
import io.karandash.api.support.rethrowCancellation

/**
 * Reduces boilerplate in typed module `initializeTyped()` methods.
 *
 * Handles both credential-resolution patterns used across the codebase:
 * - Pattern 1: `resolve()` throws [IllegalStateException] on missing required creds
 * - Pattern 2: `resolve()` returns `null` when credentials are not configured
 *
 * When credentials are missing the module reports itself unavailable (returns `false`), so a run that selects
 * the platform fails with "unavailable with current configuration" instead of sending unauthenticated requests
 * that every call then rejects. Platform services are initialized lazily for the selected platform only, never
 * during bootstrap, so detection and URL parsing do not depend on this result. A module that works without
 * credentials passes `credentialsOptional = true` and still registers.
 *
 * @param R The resolved credentials type (must be non-null)
 * @param context The plugin context for logging
 * @param platformName Human-readable platform name (e.g. "GitLab")
 * @param moduleType Human-readable module type (e.g. "provider", "comment poster", "mutator")
 * @param resolveCredentials Lambda that resolves and returns credentials, or null if not available
 * @param credentialsOptional `true` when the module is usable without credentials (the result then stays `true`)
 * @param createClient Lambda that creates the platform client from resolved credentials
 * @return `true` once the client is created, or when credentials are missing and [credentialsOptional]; `false` otherwise
 */
inline fun <R : Any> initializeWithCredentials(
    context: PluginContext,
    platformName: String,
    moduleType: String,
    resolveCredentials: () -> R?,
    credentialsOptional: Boolean = false,
    createClient: (R) -> Unit,
): Boolean {
    val credentials = try {
        resolveCredentials() ?: run {
            context.warn("$platformName credentials not configured")
            return credentialsOptional
        }
    } catch (e: IllegalStateException) {
        e.rethrowCancellation() // a cancelled suspend resolver must not read as "credentials missing"
        context.warn(e.message ?: "$platformName credentials not available")
        return credentialsOptional
    }
    createClient(credentials)
    context.logger.debug("$platformName $moduleType module initialized")
    return true
}
