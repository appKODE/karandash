package io.karandash.plugins.ai.stub

import io.karandash.api.Module
import io.karandash.api.PluginContext
import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.ai.AiCompletionResponse
import io.karandash.api.ai.AiProviderException
import io.karandash.api.ai.AiProviderModule
import io.karandash.api.module.ModuleScope
import io.karandash.models.support.KarandashJson
import kotlinx.atomicfu.atomic
import kotlinx.io.IOException
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * `ai.provider: stub`: answers every call from `ai.stub.replyFile` without any network, reporting usage 0. For
 * dry runs and CI of a config that has AI rules but no key. The file's text is the reply to every call; a JSON object
 * maps a feature to its reply instead, looked up by the request's model, then by a key the system prompt contains,
 * then `default` (a non-string value is sent as its JSON text).
 *
 * Without a readable `replyFile` [initialize] returns false, so the stub is never picked as a fallback provider.
 */
class StubAiProvider : AiProviderModule {
    override val id: String = ID
    override val name: String = "Stub AI Provider"
    override val description: String = "Canned AI replies from a file, no network, usage 0"
    override val version: String = "1.7.0"
    override val providerId: String = PROVIDER_ID
    override val defaultModel: String = PROVIDER_ID
    override val requiresApiKey: Boolean = false
    override val scope: ModuleScope get() = ModuleScope.PER_RUN

    /** The file as a per-feature map ([JsonObject]) or as one reply ([JsonPrimitive]); null before [initialize]. */
    private val replies = atomic<JsonElement?>(null)

    override suspend fun newHandle(context: PluginContext): Module = StubAiProvider()

    override suspend fun initialize(context: PluginContext): Boolean {
        val file = ((context.pluginConfig as? JsonObject)?.get(KEY_STUB) as? JsonObject)
            ?.get(KEY_REPLY_FILE)
            ?.jsonPrimitive
            ?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?: return false
        val path = Path(file).takeIf { it.isAbsolute } ?: Path(context.workingDirectory, file)
        val text = try {
            SystemFileSystem.source(path).buffered().use { it.readString() }
        } catch (e: IOException) {
            context.logger.warn("AI provider '$PROVIDER_ID': cannot read replyFile '$file': ${e.message}")
            return false
        }
        // a file that starts like a JSON object must be one: a typo there should not become the reply to every call
        val parsed = if (!text.trimStart().startsWith("{")) {
            JsonPrimitive(text)
        } else {
            try {
                KarandashJson.Default.parseToJsonElement(text) as? JsonObject
            } catch (e: SerializationException) {
                context.logger.warn("AI provider '$PROVIDER_ID': replyFile '$file' is not a JSON object: ${e.message}")
                return false
            }
        }
        replies.value = parsed
        return true
    }

    override suspend fun complete(request: AiCompletionRequest): AiCompletionResponse {
        val all = checkNotNull(replies.value) { "stub provider used before initialize()" }
        val reply = when (all) {
            is JsonObject -> lookup(all, request)
                ?: throw AiProviderException(
                    "stub: no reply for model '${request.model}' in replyFile (add a '$DEFAULT_KEY' entry)",
                )

            else -> all
        }
        val content = (reply as? JsonPrimitive)?.takeIf { it.isString }?.content ?: reply.toString()
        return AiCompletionResponse(
            content = content,
            promptTokens = 0,
            completionTokens = 0,
            totalTokens = 0,
            model = request.model.ifEmpty { PROVIDER_ID },
        )
    }

    private fun lookup(
        map: JsonObject,
        request: AiCompletionRequest,
    ): JsonElement? {
        val system = request.systemPrompt.orEmpty()
        return map[request.model]
            ?: map.entries.firstOrNull { (key, _) -> key != DEFAULT_KEY && system.contains(key) }?.value
            ?: map[DEFAULT_KEY]
    }

    companion object {
        const val ID: String = "io.karandash.ai.provider.stub"
        const val PROVIDER_ID: String = "stub"
        private const val KEY_STUB = "stub"
        private const val KEY_REPLY_FILE = "replyFile"
        private const val DEFAULT_KEY = "default"
    }
}
