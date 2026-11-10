package io.karandash.cli.plugin

import com.github.ajalt.clikt.testing.test
import io.karandash.api.PluginContext
import io.karandash.api.facet.PluginCommandSpec
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlin.test.Test
import kotlin.test.assertEquals

class PluginCommandBridgeTest {
    private val context = object : PluginContext {
        override val pluginConfig: JsonElement = JsonNull
        override val hostVersion: String = "test"
        override val workingDirectory: String = "."

        override fun env(name: String): String? = null
    }

    @Test
    fun `leaf command receives its own flags as raw args`() {
        val received = mutableListOf<String>()
        val spec = PluginCommandSpec(
            name = "ai",
            help = "group",
            subcommands = listOf(
                PluginCommandSpec("fix", "leaf", run = { ctx ->
                    received += ctx.rawArgs
                    0
                }),
            ),
        )

        val result = PluginCommandBridge.buildCommand(spec, context).test(listOf("fix", "--rule", "r1", "--message", "m"))

        assertEquals(0, result.statusCode, result.output)
        assertEquals(listOf("--rule", "r1", "--message", "m"), received)
    }

    @Test
    fun `leaf exit code is propagated`() {
        val spec = PluginCommandSpec("leaf", "leaf", run = { 2 })

        assertEquals(2, PluginCommandBridge.buildCommand(spec, context).test(emptyList()).statusCode)
    }
}
