package io.karandash.loader.bridge

import io.karandash.interpolation.KarandashEnvSecurity
import io.karandash.platform.PlatformEnvironment

/**
 * The one env rule for every guest transport: a guest sees every host variable except
 * [KarandashEnvSecurity.DENYLIST] and [KarandashEnvSecurity.isSensitive] names (tokens, API keys, secrets, passwords).
 */
internal object GuestEnv {
    /** Whether a guest may read [name]. */
    fun allows(name: String): Boolean = name !in KarandashEnvSecurity.DENYLIST && !KarandashEnvSecurity.isSensitive(name)

    /** The variables a guest sees, sorted by name: [names] it [allows], read through [lookup] (absent values drop out). */
    fun filter(
        lookup: (String) -> String?,
        names: Iterable<String> = PlatformEnvironment.names(),
    ): Map<String, String> =
        names
            .filter(::allows)
            .sorted()
            .mapNotNull { name -> lookup(name)?.let { name to it } }
            .toMap()
}
