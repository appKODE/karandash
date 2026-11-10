package io.karandash.loader.jar

import io.karandash.api.Module
import io.karandash.api.PluginContext
import io.karandash.api.ai.AiAwarePluginContext
import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.module.RuleModule
import io.karandash.loader.bridge.host.FakeAi
import io.karandash.loader.bridge.host.HostServiceDispatcher
import io.karandash.loader.bridge.host.HostTestContext
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import io.karandash.models.support.KarandashJson
import io.karandash.sdk.NoOpLogger
import io.karandash.subprocess.JarBridgeProtocol
import io.karandash.subprocess.JarBridgeRequest
import io.karandash.subprocess.JarBridgeResponse
import io.karandash.testfixtures.TestFiles
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Loaded by the bridge JVM through `META-INF/services` (written per test, so this JVM's ServiceLoader never sees it).
 * `validate` by rule id: `callbacks` logs, round-trips the host cache and asks the host AI; `count` counts calls on
 * this instance; `hang` never answers.
 */
class JarSessionProbeModule : RuleModule {
    override val id = ID
    override val name = ID
    override val version = "1.0"
    private val context = MutableStateFlow<PluginContext?>(null)
    private val calls = MutableStateFlow(0)

    override suspend fun initialize(context: PluginContext): Boolean {
        this.context.value = context
        return true
    }

    override suspend fun contributedRules() = setOf("callbacks", "count", "hang")

    override suspend fun validate(
        ruleId: String,
        mr: MergeRequest,
        configElement: JsonElement,
    ): List<Finding> {
        val message = when (ruleId) {
            "count" -> calls.updateAndGet { it + 1 }.toString()
            "hang" -> awaitCancellation()
            else -> callbacks(checkNotNull(context.value) { "not initialized" })
        }
        return listOf(Finding(rule = ruleId, message = message, severity = Severity.INFO))
    }

    private suspend fun callbacks(ctx: PluginContext): String {
        ctx.log("probe says hi")
        val cache = ctx.cacheProvider.getStringCache("probe")
        cache.put("k", "cached")
        val ai = (ctx as? AiAwarePluginContext)?.ai?.complete(AiCompletionRequest(prompt = "p", model = "m"))
        return "${cache.get("k")}|${ai?.content}"
    }

    companion object {
        const val ID = "jar-session-probe"
    }
}

/** One long-lived bridge JVM, host callbacks over the wire, timeout + respawn, old-bridge fallback. */
class JarBridgeSessionTest {
    private fun bridgeCommand(): List<String> {
        val services = TestFiles.createTestTempDirectory("jar-session").toString()
        File(services, "META-INF/services")
            .apply { mkdirs() }
            .resolve(Module::class.java.name)
            .writeText(JarSessionProbeModule::class.java.name)
        val java = File(System.getProperty("java.home"), "bin/java").path
        val classpath = System.getProperty("java.class.path") + File.pathSeparator + services
        return listOf(java, "-cp", classpath, JarBridgeServer::class.java.name)
    }

    private fun open(
        command: List<String> = bridgeCommand(),
        callTimeout: Duration = 30.seconds,
    ) = runBlocking { JarBridgeSession.open(command, callTimeout, 30.seconds, NoOpLogger) }

    private fun validate(
        session: JarBridgeSession,
        ruleId: String,
    ): JarBridgeResponse =
        runBlocking {
            session.send(
                JarBridgeRequest(
                    JarBridgeProtocol.METHOD_INVOKE,
                    JarSessionProbeModule.ID,
                    mapOf(
                        JarBridgeProtocol.PARAM_OP to "validate",
                        JarBridgeProtocol.PARAM_ARGS to KarandashJson.Wire.encodeToString(
                            ListSerializer(String.serializer()),
                            listOf(ruleId, KarandashJson.Wire.encodeToString(MergeRequest.serializer(), createTestMergeRequest()), "{}"),
                        ),
                        JarBridgeProtocol.PARAM_CONTEXT to "{}",
                    ),
                ),
            )
        }

    private fun JarBridgeResponse.message(): String =
        KarandashJson.Wire
            .decodeFromString(ListSerializer(Finding.serializer()), checkNotNull(data?.get(JarBridgeProtocol.DATA_RESULT)) { "$error" })
            .single()
            .message

    @Test
    fun `module calls reach the host log cache and AI`() {
        val session = checkNotNull(open())
        val host = HostTestContext(ai = FakeAi(reply = "from host"))
        session.register(JarSessionProbeModule.ID, HostServiceDispatcher({ JarSessionProbeModule.ID }, NoOpLogger) { host })
        try {
            assertEquals("cached|from host", validate(session, "callbacks").message())
            assertEquals(listOf("probe says hi"), host.logs)
            assertEquals(mapOf("${JarSessionProbeModule.ID}:k" to "cached"), host.cache.entries)
        } finally {
            runBlocking { session.close() }
        }
    }

    @Test
    fun `module state lives across calls and a timeout restarts the JVM`() {
        val session = checkNotNull(open(callTimeout = 3.seconds))
        try {
            assertEquals("1", validate(session, "count").message())
            assertEquals("2", validate(session, "count").message())

            assertFalse(validate(session, "hang").success)

            assertEquals("1", validate(session, "count").message())
        } finally {
            runBlocking { session.close() }
        }
    }

    @Test
    fun `a bridge without the session protocol falls back`() {
        val old = listOf("sh", "-c", "read line; echo '{\"success\":false,\"error\":\"Unknown method: hello\"}'")

        assertNull(open(command = old))
        assertNotNull(open()).let { runBlocking { it.close() } }
    }
}
