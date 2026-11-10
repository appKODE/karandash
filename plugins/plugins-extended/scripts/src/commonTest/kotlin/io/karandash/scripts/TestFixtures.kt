package io.karandash.scripts

import io.karandash.api.PluginContext
import io.karandash.api.platform.SubprocessBudget
import io.karandash.models.MergeRequest
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Minimal test fixtures for script module tests.
 */
object TestFixtures {
    fun minimalMr(): MergeRequest =
        createTestMergeRequest(
            title = "feat: test MR",
            description = "Test description",
        )
}

/**
 * Simple test plugin context.
 */
class TestPluginContext(
    private val workingDir: String = ".",
    private val envVars: Map<String, String> = emptyMap(),
    override val subprocessBudget: SubprocessBudget = SubprocessBudget.UNLIMITED,
) : PluginContext {
    override val pluginConfig: JsonElement = JsonObject(emptyMap())
    override val hostVersion: String = "1.0.0-test"
    override val workingDirectory: String = workingDir
    val logs = mutableListOf<String>()
    val warnings = mutableListOf<String>()

    override fun log(message: String) {
        logs.add(message)
    }

    override fun warn(message: String) {
        warnings.add(message)
    }

    override fun env(name: String): String? = envVars[name]
}
