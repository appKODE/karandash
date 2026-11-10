package io.karandash.plugins.ai.core.config

import kotlinx.serialization.Serializable

/**
 * `ai.thread-commands`: what the bot answers when a project member writes `@karandash why` or `@karandash ask` in a
 * reply to one of its inline threads. `why` needs no model and is always on with `ai.feedback`; `ask` costs a model
 * call from the run's AI budget, so it is off by default.
 *
 * @property ask Answer `@karandash ask <question>`; off, it gets one short "disabled" reply.
 */
@Serializable
data class ThreadCommandsConfig(
    val ask: Boolean = false,
)
