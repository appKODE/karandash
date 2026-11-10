package io.karandash.detect

import io.karandash.api.Module
import io.karandash.api.module.PlatformServiceModule

/**
 * Maps a raw platform value (full module ID or dslName) to a canonical platform ID.
 *
 * Shared between [PlatformDetector] and [PlatformUrlParser].
 *
 * @param rawPlatform Raw platform string (may be a full module ID like "io.karandash.provider.gitlab").
 * @param providers Registered provider modules to match against.
 * @return Canonical short platform ID (e.g. "gitlab", "azure-devops") or [rawPlatform] unchanged.
 */
internal suspend fun resolveCanonicalPlatform(
    rawPlatform: String,
    providers: List<PlatformServiceModule>
): String {
    val byId = providers.firstOrNull { it.id == rawPlatform }
    if (byId != null) return byId.id

    val normalized = Module.normalizeId(rawPlatform)
    for (provider in providers) {
        val providerKeys = mutableSetOf(
            Module.normalizeId(provider.id),
            Module.normalizeId(provider.dslName),
        )
        provider.aliases().forEach { providerKeys.add(Module.normalizeId(it)) }
        if (normalized in providerKeys) return provider.id
    }

    return rawPlatform
}
