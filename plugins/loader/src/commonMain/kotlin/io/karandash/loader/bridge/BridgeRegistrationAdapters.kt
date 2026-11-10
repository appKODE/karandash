package io.karandash.loader.bridge

import io.karandash.api.ExperimentalKarandashApi
import io.karandash.api.Module
import io.karandash.api.ModuleDescriptor
import io.karandash.api.ModuleType
import io.karandash.api.facet.PluginCommandSpec
import io.karandash.api.module.CliCommandModule
import io.karandash.api.module.ConfigFormatModule
import kotlinx.serialization.json.JsonElement

/**
 * Generic [CliCommandModule] adapter that wraps any [Module] with the marker interface.
 *
 * Replaces per-bridge concrete classes (JsCliCommandBridge, WasmCliCommandBridge, etc.)
 * with a single reusable adapter.
 */
internal class BridgeCliCommandAdapter<T : Module>(
    private val delegate: T,
    private val commandsProvider: suspend () -> List<PluginCommandSpec>,
) : CliCommandModule,
    Module by delegate {
    override val type: ModuleType get() = ModuleType.CLI_COMMAND

    override suspend fun cliCommands(): List<PluginCommandSpec> = commandsProvider()
}

/**
 * Generic [ConfigFormatModule] adapter that wraps any [Module] with the marker interface.
 *
 * Replaces per-bridge concrete classes (JsConfigFormatBridge, WasmConfigFormatBridge, etc.)
 * with a single reusable adapter.
 */
internal class BridgeConfigFormatAdapter<T : Module>(
    private val delegate: T,
    private val bridgeFormatId: String,
    private val bridgeFileExtensions: Set<String>,
    private val bridgePriority: Int,
    private val parseFunc: suspend (String) -> JsonElement,
    private val parseExtendsFunc: suspend (String) -> JsonElement?,
) : ConfigFormatModule,
    Module by delegate {
    override val type: ModuleType get() = ModuleType.CONFIG_FORMAT

    override val formatId: String get() = bridgeFormatId

    override val fileExtensions: Set<String> get() = bridgeFileExtensions

    override val priority: Int get() = bridgePriority

    override suspend fun parseToJsonElement(content: String): JsonElement = parseFunc(content)

    override suspend fun parseExtendsOnly(content: String): JsonElement? = parseExtendsFunc(content)
}

/**
 * Registers a bridge as one part per type it serves: [Module.type] first, then every other type its
 * [descriptor] declares ([BridgeDescriptor.servedTypes]). Each part is a [bridgeFor]-style proxy (or a CLI
 * command / config format adapter) over the same guest, so the guest answers as every type it declares.
 * Pass the result to `ModuleRegistry.registerComposite`.
 *
 * The CLI_COMMAND and CONFIG_FORMAT parts answer through one [BridgeConfigFormatPart] over [transport], whatever runs
 * the guest; the CONFIG_FORMAT part reports the bridge's [PartAware.formatIdFor] id.
 */
@OptIn(ExperimentalKarandashApi::class)
internal fun <T> T.asRegisteredModules(): List<Module> where T : DelegatingModuleBridge, T : PartAware {
    val bridge = this
    val configPart = BridgeConfigFormatPart(transport)
    // the primary part always registers, as a plain module when its own type has no proxy
    return descriptor.servedTypes(type).mapIndexedNotNull { index, partType ->
        when (partType) {
            ModuleType.CLI_COMMAND -> {
                BridgeCliCommandAdapter(bridge) { configPart.cliCommands() }
            }

            ModuleType.CONFIG_FORMAT -> {
                BridgeConfigFormatAdapter(
                    delegate = bridge,
                    bridgeFormatId = bridge.formatIdFor(ModuleType.CONFIG_FORMAT),
                    bridgeFileExtensions = configPart.fileExtensions,
                    bridgePriority = descriptor.int(ModuleDescriptor.PRIORITY) ?: ConfigFormatModule.DEFAULT_PRIORITY,
                    parseFunc = configPart::parseToJsonElement,
                    parseExtendsFunc = configPart::parseExtendsOnly,
                )
            }

            else -> {
                typedPart(bridge, partType, descriptor, transport) ?: PlainModuleProxy(bridge).takeIf { index == 0 }
            }
        }
    }
}
