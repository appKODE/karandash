@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.validators

import io.karandash.api.Module
import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import io.karandash.api.context.EnrichmentContext
import io.karandash.api.module.PlatformServiceModule
import io.karandash.api.module.RuleModule
import io.karandash.api.run.RunContext
import io.karandash.api.run.ServiceKey
import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.models.finding.Severity
import io.karandash.sdk.enrichment.EnrichmentKeys
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** fileChanges requireCoverage reads CI coverage gaps from the run context. */
class FileChangesRunContextTest {
    private class StubRun(
        override val enrichment: EnrichmentContext,
    ) : RunContext {
        override fun <T : Any> service(key: ServiceKey<T>): T? = null

        override suspend fun <M : Module> handle(
            module: M,
            context: PluginContext,
        ): M? = null

        override fun get(id: String): Module? = null

        override fun getByType(type: ModuleType): List<Module> = emptyList()

        override fun registeredIds(): Set<String> = emptySet()

        override fun hasRule(ruleId: String): Boolean = false

        override fun findRuleHandler(ruleId: String): RuleModule? = null

        override fun findByAlias(alias: String): PlatformServiceModule? = null

        override fun allAliases(): Map<String, PlatformServiceModule> = emptyMap()

        override fun aliasCollisions(): List<String> = emptyList()
    }

    private val mr = createTestMergeRequest(changes = listOf(FileChange(path = "a.kt", changeType = ChangeType.MODIFIED, additions = 1)))
    private val config: JsonElement = JsonObject(mapOf("requireCoverage" to JsonPrimitive(true)))

    private fun gaps() =
        JsonObject(
            mapOf("a.kt" to JsonArray(listOf(JsonObject(mapOf("startLine" to JsonPrimitive(2), "endLine" to JsonPrimitive(4)))))),
        )

    @Test
    fun `validate with run context adds coverage findings from enrichment`() =
        runTest {
            val run = StubRun(EnrichmentContext(mapOf(EnrichmentKeys.CI_COVERAGE_GAPS to gaps())))
            val findings = FileChangesRuleModule().validate(FileChangesRuleModule.RULE_ID, mr, config, run)
            assertEquals(1, findings.size, findings.toString())
            assertEquals(Severity.WARNING, findings[0].severity)
            assertTrue(findings[0].message.contains("2-4"), findings[0].message)
        }

    @Test
    fun `validate with run context and no enrichment adds nothing`() =
        runTest {
            val run = StubRun(EnrichmentContext.EMPTY)
            assertTrue(FileChangesRuleModule().validate(FileChangesRuleModule.RULE_ID, mr, config, run).isEmpty())
        }

    @Test
    fun `validate with a foreign rule id skips coverage findings`() =
        runTest {
            val run = StubRun(EnrichmentContext(mapOf(EnrichmentKeys.CI_COVERAGE_GAPS to gaps())))
            assertTrue(FileChangesRuleModule().validate("other", mr, config, run).isEmpty())
        }

    @Test
    fun `rule explanation is served for its own rule id only`() =
        runTest {
            val module = FileChangesRuleModule()
            assertTrue(module.ruleExplanation(FileChangesRuleModule.RULE_ID).orEmpty().contains("file changes"))
            assertNull(module.ruleExplanation("other"))
        }
}
