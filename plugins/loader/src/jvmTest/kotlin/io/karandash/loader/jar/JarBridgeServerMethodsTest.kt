package io.karandash.loader.jar

import io.karandash.api.ExperimentalKarandashApi
import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import io.karandash.api.ai.AiContextEnricherContext
import io.karandash.api.ai.AiContextEnricherModule
import io.karandash.api.exception.ContentResolveException
import io.karandash.api.module.RuleModule
import io.karandash.api.module.ScriptModule
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import io.karandash.models.support.KarandashJson
import io.karandash.sdk.content.ContentSource
import io.karandash.subprocess.JarBridgeProtocol
import io.karandash.subprocess.JarBridgeRequest
import io.karandash.testfixtures.TestFiles
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The JAR bridge server answers every method the native transport forwards, each in a freshly initialized module. */
class JarBridgeServerMethodsTest {
    /**
     * Reports the `prefix` from its plugin config, so a finding proves initialize ran with the host's context first.
     * Also a script module and an AI context enricher, so one module answers every transport method.
     */
    private class ConfiguredRule(
        private val accept: Boolean = true,
    ) : RuleModule,
        ScriptModule,
        AiContextEnricherModule {
        override val type = ModuleType.RULE
        override val id = "jar-configured"
        override val name = id
        override val version = "1.0"
        val calls = mutableListOf<String>()
        private val prefixes = mutableListOf<String>()
        private val prefix get() = prefixes.lastOrNull() ?: "uninitialized"

        override suspend fun initialize(context: PluginContext): Boolean {
            calls += "initialize"
            prefixes += (context.pluginConfig as? JsonObject)?.get("prefix")?.let { (it as JsonPrimitive).content } ?: "none"
            return accept
        }

        override suspend fun contributedRules() = setOf("configured")

        override suspend fun execute(
            scripts: List<JsonElement>,
            mr: MergeRequest,
            context: PluginContext,
        ): List<Finding> = emptyList()

        override suspend fun enrich(
            mr: MergeRequest,
            context: AiContextEnricherContext,
        ): Map<String, String> = emptyMap()

        override suspend fun ruleExplanation(ruleId: String) = "$prefix explains $ruleId"

        override suspend fun validate(
            ruleId: String,
            mr: MergeRequest,
            configElement: JsonElement,
        ): List<Finding> {
            calls += "validate"
            return listOf(Finding(rule = ruleId, message = "$prefix:${mr.title}", severity = Severity.WARNING))
        }
    }

    private val hostContext = buildJsonObject { put("pluginConfig", buildJsonObject { put("prefix", "host") }) }.toString()

    @AfterTest
    fun clear() = JarBridgeServer.moduleCache.clear()

    private fun send(
        rule: RuleModule,
        method: String,
        params: Map<String, String> = emptyMap(),
    ) = JarBridgeServer.handleRequest(
        JarBridgeRequest(method, rule.id, params + (JarBridgeProtocol.PARAM_CONTEXT to hostContext)),
    )

    private fun invoke(
        rule: RuleModule,
        op: String,
        vararg args: String,
    ) = send(
        rule,
        JarBridgeProtocol.METHOD_INVOKE,
        mapOf(
            JarBridgeProtocol.PARAM_OP to op,
            JarBridgeProtocol.PARAM_ARGS to KarandashJson.Wire.encodeToString(ListSerializer(String.serializer()), args.toList()),
        ),
    )

    private fun validate(rule: RuleModule) =
        invoke(
            rule,
            "validate",
            "configured",
            KarandashJson.Wire.encodeToString(MergeRequest.serializer(), createTestMergeRequest(title = "t")),
            "{}",
        )

    @Test
    fun `every transport method is answered`() {
        val rule = ConfiguredRule().also { JarBridgeServer.moduleCache[it.id] = it }

        val responses = listOf(
            JarBridgeProtocol.METHOD_INITIALIZE to send(rule, JarBridgeProtocol.METHOD_INITIALIZE),
            JarBridgeProtocol.METHOD_INVOKE to invoke(rule, "contributedRules"),
            JarBridgeProtocol.METHOD_SHUTDOWN to send(rule, JarBridgeProtocol.METHOD_SHUTDOWN),
        )

        assertEquals(emptyList(), responses.filterNot { it.second.success }.map { "${it.first}: ${it.second.error}" })
    }

    @Test
    fun `every op the module exports is listed by discovery`() =
        runTest {
            val exports = JarBridgeDispatcher.exports(ConfiguredRule())

            assertTrue(listOf("validate", "ruleExplanation", "contributedRules", "execute").all { it in exports }, "$exports")
        }

    @Test
    fun `validate initializes the module with the host context first`() {
        val rule = ConfiguredRule().also { JarBridgeServer.moduleCache[it.id] = it }

        val response = validate(rule)

        val findings = KarandashJson.Wire.decodeFromString(
            ListSerializer(Finding.serializer()),
            checkNotNull(response.data?.get(JarBridgeProtocol.DATA_RESULT)),
        )
        assertEquals(listOf("host:t"), findings.map { it.message })
        assertEquals(listOf("initialize", "validate"), rule.calls)
    }

    @Test
    fun `a module refusing initialize is not validated`() {
        val rule = ConfiguredRule(accept = false).also { JarBridgeServer.moduleCache[it.id] = it }

        val response = validate(rule)

        assertFalse(response.success)
        assertEquals(listOf("initialize"), rule.calls)
    }

    @Test
    fun `ruleExplanation and contributedRules reach the module`() {
        val rule = ConfiguredRule().also { JarBridgeServer.moduleCache[it.id] = it }

        val explanation = invoke(rule, "ruleExplanation", "configured")
        val rules = invoke(rule, "contributedRules")

        assertEquals("host explains configured", explanation.data?.get(JarBridgeProtocol.DATA_RESULT))
        assertEquals("[\"configured\"]", rules.data?.get(JarBridgeProtocol.DATA_RESULT))
    }

    @Test
    fun `an op the module does not implement fails`() {
        val rule = ConfiguredRule().also { JarBridgeServer.moduleCache[it.id] = it }

        assertFalse(invoke(rule, "formatId").success)
    }

    @OptIn(ExperimentalKarandashApi::class)
    @Test
    fun `resolveContent reads files under the config dir and rejects escapes`() =
        runTest {
            val dir = java.nio.file.Paths
                .get(TestFiles.createTestTempDirectory("jar-bridge-content").toString())
                .toFile()
            File(dir, "prompt.md").writeText("from file")
            File(dir.parentFile, "outside-${dir.name}.md").writeText("secret")
            val context = JarBridgePluginContext.fromJson(buildJsonObject { put("configDir", dir.path) }.toString())

            assertEquals("from file", context.resolveContent(ContentSource.LocalFile("prompt.md")))
            assertEquals(
                "a|from file",
                context.resolveContent(
                    ContentSource.Multiple(listOf(ContentSource.Inline("a"), ContentSource.LocalFile("prompt.md")), "|")
                ),
            )
            assertFailsWith<ContentResolveException> { context.resolveContent(ContentSource.LocalFile("../outside-${dir.name}.md")) }
            assertFailsWith<ContentResolveException> { context.resolveContent(ContentSource.LocalFile(File(dir, "prompt.md").path)) }
            val remote =
                assertFailsWith<ContentResolveException> { context.resolveContent(ContentSource.RemoteFile("https://example.com/p")) }
            assertTrue("not available to plugin guests" in remote.message.orEmpty())
        }
}
