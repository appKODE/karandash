package io.karandash.plugins.ai.core.config

import kotlinx.serialization.Serializable

/**
 * `ai.ollama`: [allowHosts] are reachable as the Ollama base URL besides loopback, private-network and
 * single-label hosts. Matched by name only, case-insensitively; nothing is resolved through DNS. A configured list
 * replaces [DEFAULT_ALLOW_HOSTS].
 */
@Serializable
data class AiOllamaConfig(
    val allowHosts: List<String> = DEFAULT_ALLOW_HOSTS,
) {
    companion object {
        /** Docker Desktop's name for the host machine, where a local Ollama usually runs. */
        val DEFAULT_ALLOW_HOSTS: List<String> = listOf("host.docker.internal")
    }
}
