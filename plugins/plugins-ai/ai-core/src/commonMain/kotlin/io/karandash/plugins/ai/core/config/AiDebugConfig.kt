package io.karandash.plugins.ai.core.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `ai.debug`: [prompt] `artifact` writes every AI request, after redaction and exactly as the provider gets it,
 * to per-rule files under [promptDir]. There is no mode that posts the prompt to the MR: it carries project source.
 */
@Serializable
data class AiDebugConfig(
    val prompt: PromptDumpMode = PromptDumpMode.OFF,
    val promptDir: String = DEFAULT_PROMPT_DIR,
) {
    companion object {
        const val DEFAULT_PROMPT_DIR: String = ".karandash/debug/prompts"
    }
}

/** `ai.debug.prompt`. */
@Serializable
enum class PromptDumpMode {
    @SerialName("off")
    OFF,

    @SerialName("artifact")
    ARTIFACT,
}
