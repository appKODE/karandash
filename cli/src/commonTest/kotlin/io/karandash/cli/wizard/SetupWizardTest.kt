package io.karandash.cli.wizard

import com.github.ajalt.mordant.terminal.Terminal
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cli.commands.wizard.SetupWizard
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertNotNull

class SetupWizardTest {
    @Test
    fun `wizard can be instantiated`() =
        runTest {
            val terminal = Terminal()
            val bootstrap = PluginBootstrap()
            bootstrap.loadPlugins()
            val wizard = SetupWizard(terminal, bootstrap.formatRegistry)
            assertNotNull(wizard)
            bootstrap.shutdown()
        }
}
