package io.karandash.cli.pipeline

import io.karandash.api.ModuleRegistryImpl
import io.karandash.api.PluginContext
import io.karandash.api.module.ScriptModule
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.config.Config
import io.karandash.config.ScriptEntryConfig
import io.karandash.config.ScriptMode
import io.karandash.duration.KarandashDuration
import io.karandash.engine.ValidationEngine
import io.karandash.engine.ValidationResult
import io.karandash.models.ActivePolicy
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import io.karandash.platform.PlatformProcess
import io.karandash.sdk.NoOpLogger
import io.karandash.state.RuleExecutionResult
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.time.Duration.Companion.minutes

/** Merging script/Dangerfile findings must keep every other field of the validation result. */
class ScriptExecutorResultTest {
    private class FakeScripts : ScriptModule {
        val received = mutableListOf<JsonElement>()
        override val id = "x6-script"
        override val name = id
        override val version = "1.0"

        override suspend fun initialize(context: PluginContext): Boolean = true

        override suspend fun execute(
            scripts: List<JsonElement>,
            mr: MergeRequest,
            context: PluginContext,
        ): List<Finding> = received.addAll(scripts).let { listOf(Finding(Severity.WARNING, "from script", "x6-script-rule")) }
    }

    private val base = Finding(Severity.WARNING, "base", "x6-base")

    private suspend fun stage(
        config: Config,
        scripts: FakeScripts = FakeScripts(),
    ): Pair<ConfigLoadStage, ValidationStage> {
        val registry = ModuleRegistryImpl().apply { register(scripts) }
        val bootstrap = PluginBootstrap().apply { loadPlugins() }
        val configStage = ConfigLoadStage(config, null, false, registry, bootstrap)
        val full = ValidationResult(listOf(base))
        val withExtras = full.copy(
            appliedPolicies = listOf(
                ActivePolicy(
                    name = "p",
                    condition = "true",
                    matched = true,
                    affectedModules = null,
                    forcedEnabled = null,
                    forcedSeverity = null,
                    configOverrides = null
                )
            ),
            ruleExecutionResults = listOf(RuleExecutionResult("x6-base", emptyList(), false, 0)),
        )
        return configStage to ValidationStage(ValidationEngine(config, registry), withExtras)
    }

    private fun executor() = ScriptExecutor(NoOpLogger) { _, _, _ -> }

    private val mr = MergeRequest(
        id = "1",
        iid = 7,
        title = "t",
        description = null,
        author = User(username = "dev", name = "Dev"),
        sourceBranch = "feature",
        targetBranch = "main",
        state = MergeRequestState.OPENED,
        draft = false,
        labels = emptyList(),
        changes = emptyList(),
        commits = emptyList(),
    )

    @Test
    fun `script findings keep applied policies and rule execution results`() =
        runTest(timeout = 5.minutes) {
            val (configStage, validation) = stage(Config(scripts = listOf(ScriptEntryConfig("a.js"))))
            val out = executor().runScriptsIfConfigured(configStage, mr, validation).result
            assertEquals(listOf(base.message, "from script"), out.findings.map { it.message })
            assertEquals(validation.result.appliedPolicies, out.appliedPolicies)
            assertEquals(validation.result.ruleExecutionResults, out.ruleExecutionResults)
        }

    @Test
    fun `declared script env reaches the script module and undeclared env is left out`() =
        runTest(timeout = 5.minutes) {
            val fake = FakeScripts()
            val config = Config(scripts = listOf(ScriptEntryConfig("a.js", env = listOf("JIRA_TOKEN")), ScriptEntryConfig("b.js")))
            val (configStage, validation) = stage(config, fake)

            executor().runScriptsIfConfigured(configStage, mr, validation)

            assertEquals(JsonArray(listOf(JsonPrimitive("JIRA_TOKEN"))), fake.received[0].jsonObject["env"])
            assertFalse("env" in fake.received[1].jsonObject)
        }

    @Test
    fun `scripts path mode and timeout reach the script module`() =
        runTest(timeout = 5.minutes) {
            val fake = FakeScripts()
            val entry = ScriptEntryConfig("checks/a.js", ScriptMode.SUBPROCESS, KarandashDuration.ofSeconds(42))
            val (configStage, validation) = stage(Config(scripts = listOf(entry)), fake)

            executor().runScriptsIfConfigured(configStage, mr, validation)

            val sent = fake.received.single().jsonObject
            assertEquals(JsonPrimitive("checks/a.js"), sent["path"])
            assertEquals(JsonPrimitive("subprocess"), sent["mode"])
            assertEquals(JsonPrimitive(entry.timeout.toString()), sent["timeout"])
        }

    @Test
    fun `an untouched stage is returned as is when no script finds anything`() =
        runTest {
            val (configStage, validation) = stage(Config())
            assertSame(validation, executor().runScriptsIfConfigured(configStage, mr, validation))
        }

    @Test
    fun `dangerfile findings keep applied policies and rule execution results`() =
        runTest(timeout = 5.minutes) {
            val nodeOk = try {
                PlatformProcess.execute(listOf("node", "--version")).isSuccess
            } catch (ignored: IOException) {
                false
            }
            if (!nodeOk) return@runTest
            val dir = Path(SystemFileSystem.resolve(Path(".")), "cli/build/tmp/x6-dangerfile")
            SystemFileSystem.createDirectories(dir)
            val config = Path(dir, "config.yaml")
            SystemFileSystem.sink(config).buffered().use { it.writeString("ai:\n  enabled: false\n") }
            SystemFileSystem.sink(Path(dir, "Dangerfile.js")).buffered().use { it.writeString("warn('from danger');\n") }
            val (configStage, validation) = stage(Config())
            val out = executor()
                .runDangerfileIfConfigured(configStage, mr, validation, null, config.toString())
                .result
            assertEquals(listOf(base.message, "from danger"), out.findings.map { it.message })
            assertEquals(validation.result.appliedPolicies, out.appliedPolicies)
            assertEquals(validation.result.ruleExecutionResults, out.ruleExecutionResults)
        }
}
