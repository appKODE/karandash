package io.karandash.cli.pipeline

import io.karandash.api.ModuleRegistryImpl
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.config.Config
import io.karandash.engine.ValidationEngine
import io.karandash.engine.ValidationResult
import io.karandash.logger.Logger
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import io.karandash.testfixtures.TestFiles
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/** `dangerfile:` names a script outside the config directory, which then runs. */
class DangerfileKeyBehaviourTest {
    private class Recording : Logger {
        val infos = mutableListOf<String>()

        override fun debug(message: String) = Unit

        override fun info(message: String) {
            infos += message
        }

        override fun warn(message: String) = Unit

        override fun error(message: String) = Unit
    }

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

    private suspend fun infosFor(config: Config): List<String> {
        val dir = TestFiles.createTestTempDirectory("dangerfile-key")
        val registry = ModuleRegistryImpl()
        val stage = ConfigLoadStage(config, null, false, registry, PluginBootstrap())
        val validation = ValidationStage(ValidationEngine(config, registry), ValidationResult(emptyList()))
        val log = Recording()
        ScriptExecutor(log) { _, _, _ -> }
            .runDangerfileIfConfigured(stage, mr, validation, null, "$dir/config.yaml")
        return log.infos
    }

    @Test
    fun `dangerfile names a script outside the config directory that then runs`() =
        runTest {
            val scriptDir = TestFiles.createTestTempDirectory("dangerfile-key-script")
            val script = "$scriptDir/custom-checks.js"
            TestFiles.writeText(Path(script), "// nothing to report\n")

            val withKey = infosFor(Config(dangerfile = script))
            val without = infosFor(Config())

            assertTrue(withKey.any { "Running Dangerfile" in it && "custom-checks.js" in it }, withKey.toString())
            assertTrue(without.none { "Running Dangerfile" in it }, without.toString())
        }
}
