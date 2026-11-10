package io.karandash.loader.bridge

import io.karandash.api.KarandashApi
import io.karandash.api.Module
import io.karandash.api.ModuleDescriptor
import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Bridges a guest that speaks the [BridgeAbi] op table over any [BridgeTransport] (dlopen/C interop on native, the
 * JAR subprocess bridge) to the Karandash Module interfaces.
 *
 * Per-type methods are dispatched by [DelegatingModuleBridge]; this class holds lifecycle and the per-type metadata,
 * which it reads through the transport's properties (the shared bridge modules' readers).
 *
 * @param onClose releases the transport's resources (the native library handle); called by the loader on unload.
 */
@OptIn(io.karandash.api.ExperimentalKarandashApi::class)
internal class TransportModuleBridge(
    override val transport: BridgeTransport,
    override val id: String,
    override val name: String,
    override val version: String,
    override val type: ModuleType,
    override val descriptor: BridgeDescriptor,
    private val onClose: () -> Unit = {},
) : DelegatingModuleBridge(),
    PartAware {
    // --- Module lifecycle ---

    override val defaultPriority: Int
        get() = transport.getIntProperty("defaultPriority", DEFAULT_PRIORITY)

    @OptIn(io.karandash.api.ExperimentalKarandashApi::class)
    override val minApiLevel: Int
        get() = transport.getIntProperty(BridgeConstants.PROP_MIN_API_LEVEL, KarandashApi.MIN_SUPPORTED_LEVEL)

    private val enabledByDefault: Boolean = descriptor.bool(ModuleDescriptor.ENABLED_BY_DEFAULT) ?: false

    override fun defaultEnabledFor(type: ModuleType): Boolean =
        descriptor.defaultEnabled(
            type = type,
            enabledByDefault = enabledByDefault,
        ) { transport.getBoolProperty("isDefaultEnabled", true) }

    override val defaultEnabled: Boolean = defaultEnabledFor(type)

    private val sharedInit = SharedGuestInit(composite = descriptor.types.distinct().size > 1)

    override suspend fun initialize(context: PluginContext): Boolean = sharedInit.initialize(context, ::initializeGuest)

    /** The context the guest was last initialized with: its host-service calls run on it. */
    internal val initContext = MutableStateFlow<PluginContext?>(null)

    /** A guest without an `initialize` op needs no setup: absent counts as success. */
    private suspend fun initializeGuest(context: PluginContext): Boolean {
        initContext.value = context
        return !transport.hasCapability("initialize") ||
            transport.call("initialize", BridgeContextSerializer.serialize(context))?.toBooleanStrictOrNull() ?: false
    }

    /** Keeps the library open: the run shuts rule modules down and the next run re-initializes them. */
    override suspend fun shutdown() {
        sharedInit.reset()
        transport.callVoid("shutdown")
    }

    /** Releases the transport (the native library handle); called by the loader on unload, after [shutdown]. */
    internal fun close() = onClose()

    override val dslName: String get() = descriptor.string(ModuleDescriptor.DSL_NAME) ?: id

    override val hoverDocumentation: String? get() = descriptor.string(ModuleDescriptor.HOVER_DOCUMENTATION)

    override val supportedEnrichment: List<String> =
        descriptor.stringList(ModuleDescriptor.SUPPORTED_ENRICHMENT).orEmpty()

    override val requiredEnrichment: List<String> =
        descriptor.stringList(ModuleDescriptor.REQUIRED_ENRICHMENT).orEmpty()

    override val formatId: String get() = formatIdFor(type)

    override fun formatIdFor(type: ModuleType): String =
        transport.getProperty(if (type == ModuleType.CONFIG_FORMAT) "configFormatId" else "formatId")

    private companion object {
        const val DEFAULT_PRIORITY = 100
    }
}
