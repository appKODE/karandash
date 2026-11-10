package io.karandash.loader.bridge.modules

import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import io.karandash.api.module.ScriptModule
import io.karandash.loader.bridge.BridgeContextSerializer
import io.karandash.loader.bridge.BridgePart
import io.karandash.loader.bridge.BridgeTransport
import io.karandash.loader.bridge.guarded
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.models.support.KarandashJson
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement

/**
 * Shared [ScriptModule] implementation using [BridgeTransport].
 */
internal class BridgeScriptModule(
    transport: BridgeTransport,
) : BridgePart(transport),
    ScriptModule {
    override val type: ModuleType get() = ModuleType.SCRIPT
    private val json = KarandashJson.Default

    override suspend fun execute(
        scripts: List<JsonElement>,
        mr: MergeRequest,
        context: PluginContext,
    ): List<Finding> {
        if (!transport.hasCapability(METHOD_EXECUTE)) return emptyList()
        return transport.guarded(METHOD_EXECUTE, emptyList()) {
            val scriptsJson = json.encodeToString(ListSerializer(JsonElement.serializer()), scripts)
            val mrJson = json.encodeToString(MergeRequest.serializer(), mr)
            val contextJson = BridgeContextSerializer.serialize(context)
            val result = transport.call(METHOD_EXECUTE, scriptsJson, mrJson, contextJson) ?: return emptyList()
            json.decodeFromString(ListSerializer(Finding.serializer()), result)
        }
    }

    private companion object {
        const val METHOD_EXECUTE = "execute"
    }
}
