package io.karandash.plugins.ai.core.config

import kotlinx.serialization.Serializable

/**
 * `ai.stub` for `ai.provider: stub` (no network, usage 0): [replyFile] is read relative to the working directory.
 * Its text is the reply to every call; a JSON object instead maps a feature to its reply, matched by the request's
 * model, then by a key the system prompt contains, then `default`.
 */
@Serializable
data class AiStubConfig(
    val replyFile: String? = null,
)
