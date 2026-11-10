package io.karandash.api.lifecycle

import io.karandash.api.InternalKarandashApi
import io.karandash.api.Module
import io.karandash.api.ModuleResult
import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import io.karandash.logger.Logger
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

@OptIn(InternalKarandashApi::class)
class ModuleLifecycleManagerTest {
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
        private val initResult: () -> Boolean = { true },
        private val onShutdown: suspend () -> Unit = {},
    ) : Module {
        override val name: String = id
        override val version: String = "1.0"
        override val type: ModuleType = ModuleType.RULE

        override suspend fun initialize(context: PluginContext): Boolean = initResult()

        override suspend fun shutdown() {
            shutdownLog += id
            onShutdown()
        }
    }

    private object TestContext : PluginContext {
        override val pluginConfig: JsonElement = JsonObject(emptyMap())
        override val hostVersion: String = "test"
        override val workingDirectory: String = "."

        override fun env(name: String): String? = null
    }

    @Test
    fun `shutdownAll shuts tracked modules down in reverse order`() =
        runTest {
            val log = mutableListOf<String>()
            val lifecycle = ModuleLifecycleManager(RecordingLogger())
            listOf("a", "b", "c").forEach { lifecycle.track(TestModule(it, log)) }

            lifecycle.shutdownAll()
            lifecycle.shutdownAll()

            assertEquals(listOf("c", "b", "a"), log)
            assertTrue(lifecycle.trackedIds.isEmpty())
        }

    @Test
    fun `failing shutdown is logged and the rest still shut down`() =
        runTest {
            val log = mutableListOf<String>()
            val logger = RecordingLogger()
            val lifecycle = ModuleLifecycleManager(logger)
            lifecycle.track(TestModule("a", log))
            lifecycle.track(TestModule("b", log) { error("boom") })
            lifecycle.track(TestModule("c", log))

            lifecycle.shutdownAll()

            assertEquals(listOf("c", "b", "a"), log)
            assertTrue(logger.warnings.single().contains("'b' shutdown failed: boom"))
        }

    @Test
    fun `hanging shutdown times out with a warning and the rest still shut down`() =
        runTest {
            val log = mutableListOf<String>()
            val logger = RecordingLogger()
            val lifecycle = ModuleLifecycleManager(logger, shutdownTimeout = 5.seconds)
            lifecycle.track(TestModule("a", log))
            lifecycle.track(TestModule("hang", log) { awaitCancellation() })
            lifecycle.track(TestModule("c", log))

            lifecycle.shutdownAll()

            assertEquals(listOf("c", "hang", "a"), log)
            assertTrue(logger.warnings.single().contains("'hang' shutdown timed out"))
        }

    @Test
    fun `shutdownAll with a timeout bounds each module by it instead of the default`() =
        runTest {
            val logger = RecordingLogger()
            val lifecycle = ModuleLifecycleManager(logger)
            lifecycle.track(TestModule("slow", mutableListOf()) { delay(3.seconds) })

            val elapsed = testScheduler.timeSource.measureTime { lifecycle.shutdownAll(1.seconds) }

            assertEquals(1.seconds, elapsed)
            assertTrue(logger.warnings.single().contains("'slow' shutdown timed out after 1s"))
        }

    @Test
    fun `cancellation of the caller propagates instead of being swallowed`() =
        runTest {
            val log = mutableListOf<String>()
            val logger = RecordingLogger()
            val lifecycle = ModuleLifecycleManager(logger, shutdownTimeout = 1000.seconds)
            lifecycle.track(TestModule("a", log))
            lifecycle.track(TestModule("hang", log) { awaitCancellation() })

            val job = launch { lifecycle.shutdownAll() }
            runCurrent()
            job.cancel()
            job.join()

            assertTrue(job.isCancelled)
            assertEquals(listOf("hang"), log)
            assertTrue(logger.warnings.isEmpty())
        }

    @Test
    fun `shutdown by id untracks and shuts down only that module`() =
        runTest {
            val log = mutableListOf<String>()
            val lifecycle = ModuleLifecycleManager(RecordingLogger())
            lifecycle.track(TestModule("a", log))
            lifecycle.track(TestModule("b", log))

            assertTrue(lifecycle.shutdown("a"))
            assertFalse(lifecycle.shutdown("a"))
            assertEquals(listOf("b"), lifecycle.trackedIds)
            assertEquals(listOf("a"), log)
        }

    @Test
    fun `initializeAll distinguishes skipped from failed and tracks only initialized`() =
        runTest {
            val log = mutableListOf<String>()
            val lifecycle = ModuleLifecycleManager(RecordingLogger())
            val modules = listOf(
                TestModule("ok", log),
                TestModule("skip", log, initResult = { false }),
                TestModule("fail", log, initResult = { error("bad config") }),
            )

            val report = lifecycle.initializeAll(modules) { TestContext }

            assertEquals(InitializationReport(1, 1, listOf("fail"), listOf("skip")), report)
            assertEquals(listOf("ok"), lifecycle.trackedIds)
        }

    @Test
    fun `a module that throws in initialize warns with the PLUGIN_INIT_FAILED code`() =
        runTest {
            val logger = RecordingLogger()
            val modules = listOf(TestModule("fail", mutableListOf(), initResult = { error("bad config") }))

            ModuleLifecycleManager(logger).initializeAll(modules) { TestContext }

            assertEquals(listOf("[E0301] Plugin 'fail' initialization failed: bad config"), logger.warnings)
        }

    @Test
    fun `initializeResult maps a throw to a Failure with the PLUGIN_INIT_FAILED code and cause`() =
        runTest {
            val lifecycle = ModuleLifecycleManager(RecordingLogger())
            val module = TestModule("fail", mutableListOf(), initResult = { error("bad config") })

            val result = lifecycle.initializeResult(module, TestContext)

            val failure = assertIs<ModuleResult.Failure>(result)
            assertEquals("Plugin 'fail' initialization failed: bad config", failure.errorMessage)
            assertEquals("E0301", failure.errorCode)
            assertEquals("bad config", failure.cause?.message)
            assertEquals(emptyList(), lifecycle.trackedIds)
        }

    @Test
    fun `initializeResult reports Success false for a skip and tracks only an initialized module`() =
        runTest {
            val lifecycle = ModuleLifecycleManager(RecordingLogger())

            val skipped = lifecycle.initializeResult(TestModule("skip", mutableListOf(), initResult = { false }), TestContext)
            val ok = lifecycle.initializeResult(TestModule("ok", mutableListOf()), TestContext)

            assertEquals(ModuleResult.Success(false), skipped)
            assertEquals(ModuleResult.Success(true), ok)
            assertEquals(listOf("ok"), lifecycle.trackedIds)
        }
}
