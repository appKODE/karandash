package io.karandash.loader.jar

import io.karandash.api.Module
import io.karandash.api.PluginContext
import io.karandash.api.ai.AiContextEnricherModule
import io.karandash.api.ai.AiInterceptorModule
import io.karandash.api.ai.AiPromptProcessorModule
import io.karandash.api.ai.AiRedactorModule
import io.karandash.api.ai.AiResponseTransformerModule
import io.karandash.api.support.suspendCatching
import io.karandash.helpers.guest.GuestArgs
import io.karandash.helpers.guest.GuestOpTable
import io.karandash.loader.bridge.BridgeAbi
import io.karandash.loader.bridge.CType

/**
 * One request of the JAR bridge: the op's string arguments in `BridgeAbi` order, and [contextOf], which turns a
 * serialized `BridgeContextSerializer` context into a [PluginContext] (`null` = the context the request carries).
 * [host] is the module's host-service channel in a long-lived session, `null` for a per-request JVM.
 */
internal class JarInvocation(
    val args: List<String>,
    val host: JarHostCall? = null,
    val contextOf: (String?) -> PluginContext,
) {
    operator fun get(index: Int): String = args.getOrElse(index) { "" }
}

/** One `BridgeAbi` op served for every module that [accepts]; [run] answers the op's host-side encoding. */
internal class JarBridgeOp(
    val accepts: (Module) -> Boolean,
    val run: suspend (Module, JarInvocation) -> String?,
)

/** An op served for every module implementing [T]. */
internal inline fun <reified T : Any> jarOp(noinline run: suspend T.(JarInvocation) -> String?): JarBridgeOp =
    JarBridgeOp({ it is T }) { module, call -> (module as T).run(call) }

/**
 * Serves the `BridgeAbi` ops of a JVM module over the JAR bridge, with the encodings the host's `TransportModuleBridge`
 * sends and reads. The bridge server answers `invoke` requests with [invoke] and fills discovery with [exports]
 * and [snapshot], so the native host drives a JAR module through the same bridge as a native or JS guest.
 *
 * Not bridged (documented in `plugin-bridge-matrix.md`): without a session's host channel, an interceptor that
 * rewrites the request and then proceeds (its `proceed` is answered by the host with the original request) and a
 * live `AiService` for analyzers; always `PipelineEnvironment.aiService`, the upstream `EnrichmentContext`, and the
 * non-ABI `PipelineExtension` and `OutputModule.configurePlatformContext` hooks.
 */
internal object JarBridgeDispatcher {
    /** The ops every guest runtime shares ([GuestOpTable]: validate, report, platform) plus the JVM-only tables. */
    private val ops: Map<String, JarBridgeOp> =
        GuestOpTable.OPS.mapValues { (_, op) -> JarBridgeOp(op.accepts) { module, call -> op.run(module, GuestArgs(call.args)) } } +
            JarModuleOps.OPS + JarPlatformOps.OPS + JarAiOps.OPS + JarDataOps.OPS

    /** Priority aliases a host reads per interface (`BridgeConstants.PRIORITY_PROPERTIES`). */
    private val priorityAliases: Map<String, (Module) -> Int?> =
        mapOf(
            "defaultPriority" to { it.defaultPriority },
            "interceptorPriority" to { (it as? AiInterceptorModule)?.interceptorPriority },
            "processorPriority" to { (it as? AiPromptProcessorModule)?.processorPriority },
            "transformerPriority" to { (it as? AiResponseTransformerModule)?.transformerPriority },
            "enricherPriority" to { (it as? AiContextEnricherModule)?.enricherPriority },
            "redactorPriority" to { (it as? AiRedactorModule)?.redactorPriority },
        )

    /** The ops [module] answers. */
    fun exports(module: Module): List<String> = ops.filterValues { it.accepts(module) }.keys.sorted()

    /**
     * The encoded value of every zero-argument op [module] answers, plus each priority alias; read once at discovery,
     * before `initialize`. A read that throws is left out, so the host asks for it again through [invoke].
     */
    suspend fun snapshot(
        module: Module,
        contextOf: (String?) -> PluginContext,
    ): Map<String, String> {
        val call = JarInvocation(emptyList(), contextOf = contextOf)
        val values = ops
            .filter { (name, op) -> op.accepts(module) && isProperty(name) }
            .mapNotNull { (name, op) -> suspendCatching { op.run(module, call) }.getOrNull()?.let { name to it } }
        val priorities = priorityAliases.mapNotNull { (name, read) -> read(module)?.let { name to it.toString() } }
        return (values + priorities).toMap()
    }

    /**
     * Runs [op] (or one of its aliases) on [module]; `null` is the op's "no answer". Fails when [module] does not
     * implement the op's interface.
     */
    suspend fun invoke(
        module: Module,
        op: String,
        call: JarInvocation,
    ): String? {
        val name = BridgeAbi.find(op)?.op ?: op
        val handler = ops[name]
        require(handler != null && handler.accepts(module)) { "Module '${module.id}' does not answer op '$op'" }
        return handler.run(module, call)
    }

    /** Zero-argument ops but `messages`, which reports state `initialize` sets up, and the `live` ones. */
    private fun isProperty(op: String): Boolean =
        op != "messages" && BridgeAbi.find(op)?.let { it.params.isEmpty() && it.returns != CType.VOID && !it.live } == true
}
