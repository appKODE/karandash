package io.karandash.loader.bridge

import io.karandash.api.Module
import io.karandash.api.ModuleDescriptor
import io.karandash.loader.wasm.WasmAbi
import java.io.File
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Checks [BridgeAbi.OPS] against the SDK as the checked-in `.api` dumps describe it.
 *
 * Every member of every guest-implementable SDK interface (`Plugin`, `Module`, `PipelineExtension` and
 * every `*Module` in `core/api/api/api.api`), keyed `Interface.member/arity`, must be served by an op (its
 * name or an alias with as many params, or a `covers` entry), be carried by the descriptor ([ModuleDescriptor.of]
 * emits a key of that name for the interface), or be listed in [NOT_BRIDGED] with a reason.
 * Adding an SDK member, or an overload of a bridged one, therefore fails this test until the bridges learn it or
 * the gap is written down.
 *
 * Scalar SDK members (timeouts, flags) cross through the descriptor instead of NOT_BRIDGED.
 */
class BridgeAbiParityTest {
    private val repoRoot: File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "core/api/api/api.api").isFile }

    /** `member/arity` per module interface (`Plugin`, `Module`, `PipelineExtension` and its subtypes) declared in [dump]. */
    private fun interfaceMembers(dump: File): Map<String, Set<String>> {
        val result = linkedMapOf<String, MutableSet<String>>()
        var current: MutableSet<String>? = null
        dump.forEachLine { line ->
            val header = INTERFACE_HEADER.find(line)
            when {
                header != null -> {
                    current = header.groupValues[1].takeIf { isModuleInterface(it, line) }?.let { result.getOrPut(it) { linkedSetOf() } }
                }

                line.startsWith("}") -> {
                    current = null
                }

                " fun " in line && "synthetic" !in line && " static " !in line -> {
                    current?.add("${memberName(line)}/${arity(line)}")
                }
            }
        }
        return result
    }

    private fun isModuleInterface(
        path: String,
        header: String,
    ): Boolean = simple(path) in ROOT_INTERFACES || simple(path).endsWith("Module") || MODULE_SUPERTYPE.containsMatchIn(header)

    private fun memberName(line: String): String {
        val raw = line.substringBefore(" (").substringAfterLast(' ').substringBefore('-')
        return if (GETTER.matches(raw)) raw.removePrefix("get").replaceFirstChar(Char::lowercaseChar) else raw
    }

    /** Kotlin parameter count of a dump line: its JVM descriptor's parameters, less a suspend continuation. */
    private fun arity(line: String): Int {
        val params = JVM_PARAM.findAll(line.substringAfter(" (").substringBefore(')')).map { it.value }.toList()
        return params.size - if (params.lastOrNull() == CONTINUATION) 1 else 0
    }

    private val apiInterfaces: Map<String, Set<String>> by lazy {
        interfaceMembers(File(repoRoot, "core/api/api/api.api")).filterKeys { it.substringAfterLast('/') !in EXCLUDED_INTERFACES }
    }

    private fun simple(path: String) = path.substringAfterLast('/')

    private val opKeys: Set<String> = BridgeAbi.OPS.flatMap { op -> (listOf(op.op) + op.aliases).map { "$it/${op.params.size}" } }.toSet()
    private val covers: Set<String> = BridgeAbi.OPS.flatMap { it.covers }.toSet()

    /** Descriptor keys [ModuleDescriptor.of] emits for a module implementing only [path] (plus [Module], which mixins like ConfigFormatModule lack). */
    private fun descriptorKeys(path: String): Set<String> {
        val iface = Class.forName(path.replace('/', '.'))
        val module =
            Proxy.newProxyInstance(
                iface.classLoader,
                setOf(iface, Module::class.java).toTypedArray()
            ) { _, method, _ -> stub(method.returnType) }
                as Module
        return ModuleDescriptor.of(module).properties.keys
    }

    private fun stub(type: Class<*>): Any? =
        when {
            type == String::class.java -> "x"
            type == Boolean::class.javaPrimitiveType -> false
            type == Int::class.javaPrimitiveType -> 0
            type.isEnum -> type.enumConstants.first()
            Set::class.java.isAssignableFrom(type) -> emptySet<Any>()
            List::class.java.isAssignableFrom(type) -> emptyList<Any>()
            Map::class.java.isAssignableFrom(type) -> emptyMap<Any, Any>()
            else -> null
        }

    private fun uncovered(): Set<String> =
        apiInterfaces
            .flatMap { (path, members) ->
                val name = simple(path)
                val described = descriptorKeys(path)
                members
                    .filter { member ->
                        val bare = member.substringBefore('/')
                        member !in opKeys && "$name.$bare" !in covers && "$name.$member" !in covers && bare !in described
                    }.map { "$name.$it" }
            }.toSet()

    @Test
    fun `every SDK interface member has a bridge op or a written reason`() {
        val missing = uncovered() - NOT_BRIDGED.keys
        assertTrue(
            missing.isEmpty(),
            "SDK members with no BridgeAbi op, descriptor key or NOT_BRIDGED reason: ${missing.sorted()}",
        )
    }

    @Test
    fun `NOT_BRIDGED lists only members that exist and are not bridged`() {
        val stale = NOT_BRIDGED.keys - uncovered()
        assertTrue(stale.isEmpty(), "NOT_BRIDGED entries now bridged or gone from the SDK, remove them: ${stale.sorted()}")
        assertTrue(NOT_BRIDGED.values.none { it.isBlank() }, "Every NOT_BRIDGED entry needs a reason")
    }

    @Test
    fun `every covers entry names a real SDK member`() {
        val known =
            apiInterfaces
                .flatMap { (path, members) ->
                    members.flatMap { listOf("${simple(path)}.$it", "${simple(path)}.${it.substringBefore('/')}") }
                }.toSet()
        val bogus = covers - known
        assertTrue(bogus.isEmpty(), "BridgeOp.covers entries not in api.api: ${bogus.sorted()}")
    }

    @Test
    fun `the SDK typed helpers are the only module interfaces outside core api`() {
        val sdk = interfaceMembers(File(repoRoot, "plugins/sdk/api/sdk.api")).keys
        val unexpected = sdk.filterNot { it.startsWith("io/karandash/sdk/typed/Typed") || simple(it) in EXCLUDED_INTERFACES }
        assertTrue(unexpected.isEmpty(), "New guest-facing SDK interfaces need bridge parity entries: $unexpected")
    }

    @Test
    fun `every WasmAbi export is an op or a listed host-side name`() {
        val wasmNames = BridgeAbi.OPS.map { it.wasm }.toSet()
        val constants =
            WasmAbi::class.java.declaredFields
                .filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java }
                .onEach { it.isAccessible = true }
                .associate { it.name to it.get(null) as String }
        val unknown = constants.filter { (_, value) -> value !in wasmNames && value !in WASM_HOST_SIDE }
        assertTrue(unknown.isEmpty(), "WasmAbi names with no BridgeAbi op: $unknown")
        val staleHostSide = WASM_HOST_SIDE.keys - constants.values.toSet()
        assertTrue(staleHostSide.isEmpty(), "WASM_HOST_SIDE entries no longer in WasmAbi: $staleHostSide")
    }

    private companion object {
        val INTERFACE_HEADER = Regex("""^public abstract interface class (io/karandash/(?:\w+/)*\w+)\b""")
        val GETTER = Regex("get[A-Z].*")
        val JVM_PARAM = Regex("""\[*(?:[ZBCSIJFD]|L[^;]+;)""")
        const val CONTINUATION = "Lkotlin/coroutines/Continuation;"
        val MODULE_SUPERTYPE = Regex("""[ ,]io/karandash/api/Module\b""")

        /** Interfaces in the dumps a guest does not implement directly. */
        val EXCLUDED_INTERFACES: Map<String, String> =
            mapOf(
                "PseudoModuleMarker" to "host-only marker for config-key pseudo modules; no guest ever is one",
                "ModuleScopedPluginContextFactory" to "host-side context factory, not a module",
            )

        /** Also the [io.karandash.api.module.PlatformServiceModule] super-interfaces, which do not extend `Module`. */
        val ROOT_INTERFACES = setOf("Plugin", "Module", "PipelineExtension", "MergeRequestMutator", "MergeRequestCommentPoster")

        /**
         * Members no runtime serves, keyed `Interface.member/arity` so each overload needs its own reason.
         * `// reason:` per group.
         */
        val NOT_BRIDGED: Map<String, String> =
            buildMap {
                // reason: derived by the host from the module id / format id; a guest never overrides it
                listOf(
                    "Module",
                    "FormatModule",
                    "OutputModule",
                    "ReporterModule"
                ).forEach { put("$it.configKey/0", "host-derived from the id") }
                // reason: LSP and DSL metadata read from the in-process module only; guests have no DSL surface
                put("Module.scope/0", "host registration scope, decided by the loader")
                put("Module.newHandle/1", "per-run handle factory; a bridged guest is its own handle")
                // reason: these take host objects (registries, lambdas) that cannot cross a string ABI
                put("Module.registerPatterns/1", "takes a host PatternRegistry")
                put("Module.registerPredicates/1", "takes a host predicate registry")
                put("OutputModule.configurePlatformContext/5", "takes live host platform objects")
                put("SchemaAwareFormatModule.withRuleSchemas/1", "takes host schema objects")
                put("AiAnalyzerModule.resultSerializer/0", "a KSerializer; host-only")
                put("MergeRequestCommentPoster.findCommentBody/3", "notification state read-back; a guest without it posts every run")
                put("MergeRequestCommentPoster.listMarkedComments/3", "backs findCommentBody, which is not bridged either")
                put("PlatformServiceModule.fetchFindingFeedback/2", "feedback is only counted; a guest platform reports none")
                put(
                    "ProfileModule.policies/0",
                    "conditional policies come from in-process profiles only; a guest profile contributes rule configs"
                )
                put("AiProviderModule.supportsContextBlocks/0", "the complete op carries one prompt string; the host flattens blocks")
                // reason: base-branch file reads are a trust boundary kept to in-process extensions
                listOf(
                    "baseFiles",
                    "onBaseFiles"
                ).forEach { put("PipelineExtension.$it/1", "in-process only: guests get no base-branch files") }
                // reason: host-side overloads that add a host object; the default delegates to the bridged form
                put("PlatformServiceModule.fetchMergeRequest/3", "FetchOptions stay host-side; a guest fetches with its own defaults")
                put("PlatformServiceModule.fetchMergeRequestByUrl/2", "FetchOptions stay host-side; a guest fetches with its own defaults")
                put("RuleModule.initialize/2", "enrichment binding prefixes stay host-side; the initialize op serves initialize(context)")
                put("RuleModule.validate/4", "RunContext is a live host object; the validate op serves validate(ruleId, mr, config)")
            }
    }
}
