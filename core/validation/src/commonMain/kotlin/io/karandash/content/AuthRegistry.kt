package io.karandash.content

import io.karandash.config.AuthConfig

/**
 * Registry of named auth configurations for content resolution.
 * Constructed from the top-level `auth:` config block.
 */
class AuthRegistry(
    private val configs: Map<String, AuthConfig>
) {
    /** Resolve a named auth config. Throws [IllegalStateException] if not found. */
    fun resolve(name: String): AuthConfig = configs[name] ?: error("Unknown auth reference '$name'. Available: ${configs.keys}")

    /** Returns true if a config with [name] exists. */
    fun has(name: String): Boolean = name in configs

    companion object {
        /** Empty registry with no configured auth entries. */
        val EMPTY: AuthRegistry = AuthRegistry(emptyMap())
    }
}
