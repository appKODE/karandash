package io.karandash.loader.bridge

import io.karandash.api.Module
import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import io.karandash.api.config.ConfigProperty
import io.karandash.api.finding.ModuleMessage
import io.karandash.api.support.rethrowCancellation
import io.karandash.api.support.suspendCatching
import io.karandash.loader.BridgeDeserializer
import io.karandash.loader.BridgeUtils
import io.karandash.models.support.KarandashJson
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Shared implementation of [Module] using [BridgeTransport].
 */
internal class BridgeModuleCore(
    private val transport: BridgeTransport
) : Module {
    private val json = KarandashJson.Default

    override val id: String = transport.getProperty(BridgeConstants.PROP_ID)
    override val name: String = transport.getProperty(BridgeConstants.PROP_NAME)
    override val version: String = transport.getProperty(BridgeConstants.PROP_VERSION)
    override val type: ModuleType = BridgeUtils.parseModuleType(transport.getProperty("type"), "plugin")
    override val description: String get() = transport.getProperty("description").ifEmpty { name }

    override suspend fun initialize(context: PluginContext): Boolean {
        if (!transport.hasCapability("initialize")) return true
        val contextJson = BridgeContextSerializer.serialize(context)
        val result = transport.call("initialize", contextJson)
        return result?.toBooleanStrictOrNull() ?: true
    }

    override suspend fun shutdown() {
        if (!transport.hasCapability("shutdown")) return
        try {
            transport.callVoid("shutdown")
        } catch (ignored: Exception) {
            // boundary: guest plugin bridge call
            ignored.rethrowCancellation()
        }
    }

    override suspend fun defaultConfig(): JsonElement {
        if (!transport.hasCapability("defaultConfig")) return JsonObject(emptyMap())
        val result = transport.call("defaultConfig") ?: return JsonObject(emptyMap())
        return suspendCatching { json.parseToJsonElement(result) }.getOrDefault(JsonObject(emptyMap()))
    }

    override suspend fun configSchema(): List<ConfigProperty> {
        if (!transport.hasCapability("configSchema")) return emptyList()
        val result = transport.getProperty("configSchema")
        return BridgeDeserializer.parseConfigProperties(result)
    }

    override suspend fun messages(): List<ModuleMessage> = transport.readMessages()
}
