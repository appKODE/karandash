package io.karandash.loader.js

import io.karandash.api.ModuleRegistryImpl
import io.karandash.api.ai.AiAnalyzerModule
import io.karandash.loader.RecordingLogger
import io.karandash.loader.bridge.host.FakeAi
import io.karandash.loader.bridge.host.HostTestContext
import io.karandash.loader.bridge.host.RecordingBudget
import io.karandash.loader.bridge.host.analyzerContext
import io.karandash.loader.createDirectories
import io.karandash.loader.deleteTestDirectory
import io.karandash.loader.writeText
import io.karandash.testfixtures.TestFiles
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** A JS guest reaches the host services through the `ctx.*` shims over `__karandash_host_call`. */
class JsHostCallTest {
    private val dir = TestFiles.createTestTempDirectory("js-host").also { createDirectories(it) }
    private val registry = ModuleRegistryImpl()
    private val logger = RecordingLogger()

    @AfterTest
    fun cleanup() = deleteTestDirectory(dir)

    private suspend fun analyzer(analyzeBody: String): AiAnalyzerModule {
        Path("$dir/js-host.js").writeText(
            """
            var ctx = null;
            module.exports = {
                id: "js-host", name: "js-host", version: "1.0.0", type: "AI_ANALYZER",
                analyzerId: "js-host",
                initialize(c) { ctx = c; return true; },
                analyze(mrJson, budgetJson, model, configJson) { $analyzeBody }
            };
            """.trimIndent(),
        )
        JsPluginLoader(registry, logger, guestCallTimeout = 1.seconds).loadFromDirectories(listOf(dir), HostTestContext())
        return registry.get("js-host") as AiAnalyzerModule
    }

    @Test
    fun `shims serve cache content log and ai with the service and budget of the analyze call`() =
        runTest {
            val module =
                analyzer(
                    """
                    ctx.cache.put("c", "k", "v");
                    var content = ctx.content.resolve("inline text");
                    var remote = ctx.content.resolve({ url: "https://example.com/x" });
                    var reply = ctx.ai.complete({ prompt: "p" });
                    ctx.log.warn("warned");
                    var parts = [ctx.cache.get("c", "k"), content.content, remote.err ? "err" : "ok", reply.content, reply.usage.totalTokens];
                    return [{ severity: "INFO", message: parts.join("|"), rule: "js-host" }];
                    """.trimIndent(),
                )
            val context = HostTestContext()
            assertTrue(module.initialize(context))
            val ai = FakeAi(reply = "llm")
            val budget = RecordingBudget()

            val findings = module.analyze(analyzerContext(ai, budget))

            assertEquals(listOf("v|inline text|err|llm|10"), findings.map { it.message }, "${logger.warnings}")
            assertEquals(mapOf("js-host:k" to "v"), context.cache.entries)
            assertEquals(listOf("warned"), context.warnings)
            assertEquals("analyzer-model", ai.requests.single().model)
            assertEquals(listOf(10), budget.spent)
        }

    @Test
    fun `time in a host call does not count against the guest timeout`() =
        runTest {
            val module =
                analyzer(
                    """return [{ severity: "INFO", message: ctx.ai.complete("p").content, rule: "js-host" }];""",
                )
            assertTrue(module.initialize(HostTestContext()))
            // longer than the 1 s guest timeout, but spent in the host
            val slowAi = FakeAi(reply = "slow") { delay(1500.milliseconds) }

            val findings = module.analyze(analyzerContext(slowAi))

            assertEquals(listOf("slow"), findings.map { it.message }, "${logger.warnings}")
        }
}
