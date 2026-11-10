package io.karandash.interpolation

/**
 * Canonical denylist of environment variables that must not be exposed
 * through config interpolation or plugin contexts.
 *
 * Single source of truth - both CLI config parsing and plugin bootstrap
 * reference this set.
 */
object KarandashEnvSecurity {
    /**
     * System env vars that cannot be declared in config for security reasons.
     * These could leak sensitive system information if exposed through config interpolation.
     *
     * The first-party AWS chain (`io.karandash.crypto.AwsCredentialChain`, used by CodeCommit and the S3 remote
     * cache) reads `AWS_SECRET_ACCESS_KEY`, `AWS_SESSION_TOKEN` and `HOME` directly via `PlatformEnvironment`; the
     * denylist still keeps them from `${...}` interpolation and from third-party plugins.
     */
    val DENYLIST: Set<String> = setOf(
        "PATH",
        "HOME",
        "USER",
        "SHELL",
        "LOGNAME",
        "LANG",
        "SSH_AUTH_SOCK",
        "SSH_AGENT_PID",
        "SSH_PRIVATE_KEY",
        "AWS_SECRET_ACCESS_KEY",
        "AWS_SESSION_TOKEN",
        "GOOGLE_APPLICATION_CREDENTIALS",
    )

    /**
     * Whether [name] looks like a credential (`*TOKEN*`, `*_API_KEY`, `*SECRET*`, `*PASSWORD*`, case-insensitive).
     *
     * Applied only where code the user did not write reads env by name: JS plugins and policy conditions. It is not
     * part of [DENYLIST], so `${KARANDASH_GITLAB_TOKEN}` in a config still interpolates.
     */
    fun isSensitive(name: String): Boolean {
        val upper = name.uppercase()
        return "TOKEN" in upper || upper.endsWith("_API_KEY") || "SECRET" in upper || "PASSWORD" in upper
    }

    /** [lookup] that answers `null` for [DENYLIST] and [isSensitive] names. */
    fun withoutSecrets(lookup: (String) -> String?): (String) -> String? =
        { name -> if (name in DENYLIST || isSensitive(name)) null else lookup(name) }
}
