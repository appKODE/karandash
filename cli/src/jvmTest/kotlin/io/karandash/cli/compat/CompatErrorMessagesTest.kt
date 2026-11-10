package io.karandash.cli.compat

import io.karandash.api.InternalKarandashApi
import io.karandash.api.PluginContext
import io.karandash.api.facet.PluginCommandSpec
import io.karandash.api.lifecycle.ModuleLifecycleManager
import io.karandash.api.module.CliCommandModule
import io.karandash.logger.Logger
import io.karandash.platforms.gitlab.GitLabCredentials
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test

/**
 * Plugin-init failures carry a catalog code prefix. A missing provider token is not a
 * failure: it resolves anonymously and `checkAuth` reports it. The golden freezes both so the next change shows up
 * in `tools/ci/compat-diff.sh`.
 */
@OptIn(InternalKarandashApi::class)
class CompatErrorMessagesTest {
    private class RecordingLogger : Logger {
        val warnings = mutableListOf<String>()

        override fun debug(message: String) = Unit

        override fun info(message: String) = Unit

        override fun warn(message: String) {
            warnings += message
        }

        override fun error(message: String) = Unit
    }

    private object TestContext : PluginContext {
        override val pluginConfig: JsonElement = JsonObject(emptyMap())
        override val hostVersion: String = "test"
        override val workingDirectory: String = "."

        override fun env(name: String): String? = null
    }

    private val failingModule = object : CliCommandModule {
        override val id = "broken-plugin"
        override val name = "broken-plugin"
        override val version = "1.0"

        override suspend fun cliCommands(): List<PluginCommandSpec> = emptyList()

        override suspend fun initialize(context: PluginContext): Boolean = error("bad config")
    }

    @Test
    fun `plugin init failures keep their message and a missing provider token resolves anonymously`() =
        runTest {
            val logger = RecordingLogger()
            ModuleLifecycleManager(logger).initializeAll(listOf(failingModule)) { TestContext }
            val pluginInit = logger.warnings.joinToString("\n")
            val provider = GitLabCredentials.resolve(JsonObject(emptyMap()), { null })

            Golden.assertAllMatch(
                listOfNotNull(
                    Golden.check(
                        "output/error-messages.txt",
                        "plugin-init: $pluginInit\nprovider: token=${provider.token} jobToken=${provider.jobToken}\n"
                    )
                ),
            )
        }
}
