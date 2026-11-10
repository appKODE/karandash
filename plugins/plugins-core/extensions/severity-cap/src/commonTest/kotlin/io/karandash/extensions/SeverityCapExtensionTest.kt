package io.karandash.extensions

import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import io.karandash.logger.Logger
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SeverityCapExtensionTest {
    private class TestContext : PluginContext {
        val warnings = mutableListOf<String>()
        override val logger: Logger = object : Logger {
            override fun debug(message: String) {}

            override fun info(message: String) {}

            override fun warn(message: String) {
                warnings += message
            }

            override fun error(message: String) {}
        }
        override val pluginConfig: JsonElement = JsonObject(emptyMap())
        override val hostVersion: String = "test"
        override val workingDirectory: String = "/tmp"

        override fun log(message: String) {}

        override fun warn(message: String) {}

        override fun env(name: String): String? = null
    }

    private val mr = MergeRequest(
        id = "1",
        iid = 1,
        title = "t",
        description = null,
        sourceBranch = "feature/x",
        targetBranch = "develop",
        author = User("dev", "Dev"),
        state = MergeRequestState.OPENED,
        draft = false,
        labels = emptyList(),
        commits = emptyList(),
        changes = emptyList(),
    )

    private val findings = listOf(
        Finding(Severity.ERROR, "e", "commit-messages"),
        Finding(Severity.WARNING, "w", "commit-messages"),
        Finding(Severity.ERROR, "e", "title-format"),
        Finding(Severity.INFO, "i", "title-format"),
    )

    private fun obj(vararg pairs: Pair<String, JsonElement>) = JsonObject(mapOf(*pairs))

    private suspend fun cap(config: JsonElement?): List<Pair<String, Severity>> {
        val extension = SeverityCapExtension()
        extension.initialize(TestContext())
        return extension.afterValidation(findings, mr, config).map { it.rule to it.severity }
    }

    @Test
    fun `no cap leaves findings untouched`() =
        runTest {
            assertEquals(findings.map { it.rule to it.severity }, cap(obj("rules" to obj())))
            assertEquals(findings.map { it.rule to it.severity }, cap(null))
        }

    @Test
    fun `global warning cap lowers errors only`() =
        runTest {
            val capped = cap(obj("severityCap" to JsonPrimitive("warning")))
            assertEquals(
                listOf(
                    "commit-messages" to Severity.WARNING,
                    "commit-messages" to Severity.WARNING,
                    "title-format" to Severity.WARNING,
                    "title-format" to Severity.INFO,
                ),
                capped,
            )
        }

    @Test
    fun `rule cap overrides the global cap`() =
        runTest {
            val config = obj(
                "severityCap" to JsonPrimitive("info"),
                "rules" to obj("titleFormat" to obj("severityCap" to JsonPrimitive("error"))),
            )
            assertEquals(
                listOf(
                    "commit-messages" to Severity.INFO,
                    "commit-messages" to Severity.INFO,
                    "title-format" to Severity.ERROR,
                    "title-format" to Severity.INFO,
                ),
                cap(config),
            )
        }

    @Test
    fun `rule cap alone caps that rule only and kebab keys are read`() =
        runTest {
            val config = obj("rules" to obj("commit-messages" to obj("severity-cap" to JsonPrimitive("warning"))))
            assertEquals(
                listOf(
                    "commit-messages" to Severity.WARNING,
                    "commit-messages" to Severity.WARNING,
                    "title-format" to Severity.ERROR,
                    "title-format" to Severity.INFO,
                ),
                cap(config),
            )
        }

    @Test
    fun `unknown cap value is ignored with a warning`() =
        runTest {
            val context = TestContext()
            val extension = SeverityCapExtension()
            extension.initialize(context)
            val result = extension.afterValidation(findings, mr, obj("severityCap" to JsonPrimitive("loud")))
            assertEquals(findings, result)
            assertTrue(context.warnings.single().contains("loud"))
        }

    @Test
    fun `runs as the last pipeline extension`() {
        val extension = SeverityCapExtension()
        assertEquals(ModuleType.PIPELINE_EXTENSION, extension.type)
        assertTrue(extension.defaultPriority > 50)
    }
}
