package io.karandash.cli.plugin

import com.github.ajalt.clikt.testing.test
import io.karandash.api.ModuleRegistryImpl
import io.karandash.api.PluginContext
import io.karandash.api.facet.PluginCommandSpec
import io.karandash.api.module.CliCommandModule
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cli.TestFileHelpers
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PluginBootstrapTest {
    private lateinit var tempDir: Path

    @BeforeTest
    fun setup() {
        tempDir = TestFileHelpers.createTempDir("bootstrap-test")
    }

    @AfterTest
    fun cleanup() {
        TestFileHelpers.deleteTempDir(tempDir)
    }

    @Test
    fun `loadPluginsFromDirs from empty directory loads built-ins only`() =
        runTest {
            val registry = ModuleRegistryImpl()
            val bootstrap = PluginBootstrap(registry)

            val result = bootstrap.loadPluginsFromDirs(listOf(tempDir))

            assertTrue(result.loaded.isNotEmpty(), "Built-in modules should be loaded")
            assertTrue(result.failed.isEmpty())
            assertEquals(0, bootstrap.cache().size())
        }

    @Test
    fun `registry is accessible`() {
        val registry = ModuleRegistryImpl()
        val bootstrap = PluginBootstrap(registry)

        assertTrue(bootstrap.registry() === registry)
    }

    @Test
    fun `cache is accessible`() {
        val bootstrap = PluginBootstrap()

        assertNotNull(bootstrap.cache())
        assertEquals(0, bootstrap.cache().size())
    }

    @Test
    fun `shutdown on empty bootstrap does not throw`() =
        runTest {
            val bootstrap = PluginBootstrap()
            bootstrap.shutdown()
            // No exception expected
        }

    @Test
    fun `loadPluginsFromDirs with non-existent directories loads built-ins and has no failures`() =
        runTest {
            val bootstrap = PluginBootstrap()
            val nonExistent = Path(tempDir, "no-such-dir")

            val result = bootstrap.loadPluginsFromDirs(listOf(nonExistent))

            assertTrue(result.loaded.isNotEmpty(), "Built-in modules should be loaded")
            assertTrue(result.failed.isEmpty())
        }

    @Test
    fun `built-in modules are loaded via bootstrap and shutdown cleans up`() =
        runTest {
            val registry = ModuleRegistryImpl()
            val bootstrap = PluginBootstrap(registry)

            val result = bootstrap.loadPluginsFromDirs(listOf(tempDir))

            assertTrue(result.loaded.isNotEmpty(), "Expected built-in modules to be loaded")
            assertTrue(result.failed.isEmpty())

            bootstrap.shutdown()
            // Verify shutdown completes without exception
        }

    // CliPluginContext (now BootstrapPluginContext) tests moved to
    // plugins/bootstrap/src/commonTest/.../BootstrapPluginContextEnvAllowlistTest.kt

    @Test
    fun pluginCommandModuleContributesSubcommand() =
        runTest {
            val registry = ModuleRegistryImpl()
            val bootstrap = PluginBootstrap(registry)

            // Create a fake CliCommandModule with one command
            val fakeModule = object : CliCommandModule {
                override val id: String = "test-cli-command"
                override val name: String = "Test CLI Command"
                override val version: String = "1.0.0"

                override suspend fun initialize(context: PluginContext): Boolean = true

                override suspend fun cliCommands(): List<PluginCommandSpec> =
                    listOf(
                        PluginCommandSpec(
                            name = "test-command",
                            help = "A test command"
                        )
                    )

                override suspend fun shutdown() {}
            }

            // Register the fake module
            registry.register(fakeModule)

            // Build plugin CLI commands with reserved names
            val reservedNames = setOf("validate", "wizard", "init", "doctor")
            val commands = buildPluginCliCommands(registry, bootstrap, reservedNames)

            assertTrue(commands.isNotEmpty(), "Should have at least one command from fake module")
            assertTrue(commands.any { it.commandName == "test-command" }, "Should contain test-command")
        }

    @Test
    fun conflictingCommandNameIsSkipped() =
        runTest {
            val registry = ModuleRegistryImpl()
            val bootstrap = PluginBootstrap(registry)

            // Create a fake CliCommandModule with a reserved name
            val fakeModule = object : CliCommandModule {
                override val id: String = "conflicting-cli-command"
                override val name: String = "Conflicting CLI Command"
                override val version: String = "1.0.0"

                override suspend fun initialize(context: PluginContext): Boolean = true

                override suspend fun cliCommands(): List<PluginCommandSpec> =
                    listOf(
                        PluginCommandSpec(
                            name = "validate",
                            help = "Conflicting validate command"
                        )
                    )

                override suspend fun shutdown() {}
            }

            // Register the module with conflicting name
            registry.register(fakeModule)

            // Build plugin CLI commands with "validate" as reserved
            val reservedNames = setOf("validate", "wizard", "init", "doctor")
            val commands = buildPluginCliCommands(registry, bootstrap, reservedNames)

            // The conflicting "validate" command should NOT be included
            assertTrue(
                commands.none { it.commandName == "validate" },
                "Should not include conflicting 'validate' command"
            )
        }

    @Test
    fun pluginCommandInitializesAndShutsDownItsModule() =
        runTest {
            val registry = ModuleRegistryImpl()
            val events = mutableListOf<String>()
            registry.register(
                object : CliCommandModule {
                    override val id: String = "lifecycle-cli-command"
                    override val name: String = "Lifecycle CLI Command"
                    override val version: String = "1.0.0"

                    override suspend fun initialize(context: PluginContext): Boolean = events.add("initialize")

                    override suspend fun cliCommands(): List<PluginCommandSpec> =
                        listOf(
                            PluginCommandSpec(name = "lifecycle", help = "", run = {
                                events.add("run")
                                0
                            }),
                        )

                    override suspend fun shutdown() {
                        events.add("shutdown")
                    }
                },
            )

            // the command loads the config when it runs, which needs the format parsers loaded
            val bootstrap = PluginBootstrap(registry).also { it.loadPluginsFromDirs(emptyList()) }
            val command = buildPluginCliCommands(registry, bootstrap, emptySet()).single { it.commandName == "lifecycle" }
            val result = command.test(emptyList())

            assertEquals(0, result.statusCode, result.output)
            assertEquals(listOf("initialize", "run", "shutdown"), events)
        }
}
