package io.karandash.cli.plugin

import io.karandash.api.facet.PluginCommandSpec
import kotlin.test.Test
import kotlin.test.assertFailsWith

class PluginCommandSpecTest {
    @Test
    fun `spec with run handler only is valid`() {
        PluginCommandSpec(name = "cmd", help = "help", run = { 0 })
    }

    @Test
    fun `spec with subcommands only is valid`() {
        PluginCommandSpec(
            name = "cmd",
            help = "help",
            subcommands = listOf(PluginCommandSpec("sub", "sub help", run = { 0 }))
        )
    }

    @Test
    fun `spec with both subcommands and run throws`() {
        assertFailsWith<IllegalArgumentException> {
            PluginCommandSpec(
                name = "cmd",
                help = "help",
                subcommands = listOf(PluginCommandSpec("sub", "sub help", run = { 0 })),
                run = { 0 }
            )
        }
    }

    @Test
    fun `spec with neither subcommands nor run is valid leaf`() {
        PluginCommandSpec(name = "cmd", help = "help")
    }
}
