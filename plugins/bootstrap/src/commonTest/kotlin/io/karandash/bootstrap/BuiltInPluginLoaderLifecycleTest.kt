package io.karandash.bootstrap

import io.karandash.api.InternalKarandashApi
import io.karandash.api.Module
import io.karandash.api.ModuleRegistry
import io.karandash.api.ModuleRegistryImpl
import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import io.karandash.api.facet.PluginCommandSpec
import io.karandash.api.module.CliCommandModule
import io.karandash.logger.Logger
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(InternalKarandashApi::class)
class BuiltInPluginLoaderLifecycleTest {
    private class RecordingLogger : Logger {
        val warnings = mutableListOf<String>()

        override fun debug(message: String) = Unit

        override fun info(message: String) = Unit

        override fun warn(message: String) {
            warnings += message
        }

        override fun error(message: String) = Unit
    }

    private class TestModule(
        override val id: String,
        private val shutdownLog: MutableList<String>,
        private val onShutdown: suspend () -> Unit = {},
    ) : Module {
        override val name: String = id
        override val version: String = "1.0"
        override val type: ModuleType = ModuleType.RULE

        override suspend fun initialize(context: PluginContext): Boolean = true

        override suspend fun shutdown() {
            shutdownLog += id
            onShutdown()
        }
    }

    private val context = BootstrapPluginContext(moduleId = null, hostVersion = "1.0", workingDirectory = ".")

    private val registry = ModuleRegistryImpl()

    private suspend fun load(
        logger: Logger,
        vararg modules: Module,
    ): BuiltInPluginLoader {
        val factories = modules.map { module -> { _: ModuleRegistry -> module } }
        return BuiltInPluginLoader(registry, logger, factories).also { it.loadFromDirectories(emptyList(), context) }
    }

    @Test
    fun `unloadAll shuts down in reverse order isolating failures and timeouts`() =
        runTest {
            val log = mutableListOf<String>()
            val logger = RecordingLogger()
            val loader = load(
                logger,
                TestModule("a", log),
                TestModule("fails", log) { error("boom") },
                TestModule("hangs", log) { awaitCancellation() },
                TestModule("d", log),
            )

            loader.unloadAll()

            assertEquals(listOf("d", "hangs", "fails", "a"), log)
            assertEquals(2, logger.warnings.size)
            assertTrue(logger.warnings.any { "'fails' shutdown failed: boom" in it })
            assertTrue(logger.warnings.any { "'hangs' shutdown timed out" in it })
            assertTrue(registry.registeredIds().isEmpty())
        }

    @Test
    fun `timeouts pluginShutdown 3 seconds bounds a hanging module shutdown to 3 seconds`() =
        runTest {
            val log = mutableListOf<String>()
            val logger = RecordingLogger()
            val loader = load(logger, TestModule("hangs", log) { awaitCancellation() })

            loader.unloadAll(3.seconds)

            assertEquals(3_000L, testScheduler.currentTime)
            assertTrue(logger.warnings.any { "'hangs' shutdown timed out" in it })
        }

    @Test
    fun `unloadAll propagates cancellation of the caller`() =
        runTest {
            val log = mutableListOf<String>()
            val loader = load(RecordingLogger(), TestModule("a", log), TestModule("hangs", log) { awaitCancellation() })

            val job = launch { loader.unloadAll() }
            runCurrent()
            job.cancel()
            job.join()

            assertEquals(listOf("hangs"), log)
        }

    @Test
    fun `unload shuts down only the requested module`() =
        runTest {
            val log = mutableListOf<String>()
            val loader = load(RecordingLogger(), TestModule("a", log), TestModule("b", log))

            assertTrue(loader.unload("a"))

            assertEquals(listOf("a"), log)
            assertEquals(setOf("b"), registry.registeredIds())
        }

    @Test
    fun `a module that throws in initialize is a LoadFailure with the PLUGIN_INIT_FAILED code`() =
        runTest {
            val failing = object : CliCommandModule {
                override val id = "bad"
                override val name = "bad"
                override val version = "1.0"

                override suspend fun cliCommands(): List<PluginCommandSpec> = emptyList()

                override suspend fun initialize(context: PluginContext): Boolean = error("bad config")
            }
            val loader = BuiltInPluginLoader(registry, RecordingLogger(), listOf({ _: ModuleRegistry -> failing }))

            val result = loader.loadFromDirectories(emptyList(), context)

            assertEquals(
                listOf("bad: [E0301] Plugin 'bad' initialization failed: bad config"),
                result.failed.map { "${it.path}: ${it.reason}" },
            )
            assertTrue(registry.registeredIds().isEmpty())
        }
}
