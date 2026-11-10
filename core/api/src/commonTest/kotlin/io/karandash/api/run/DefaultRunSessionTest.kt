package io.karandash.api.run

import io.karandash.api.ExperimentalKarandashApi
import io.karandash.api.Module
import io.karandash.api.ModuleRegistryImpl
import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import io.karandash.api.module.ModuleScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame

@OptIn(ExperimentalKarandashApi::class)
class DefaultRunSessionTest {
    private class Ctx(
        override val pluginConfig: JsonElement,
    ) : PluginContext {
        override val hostVersion: String = "test"
        override val workingDirectory: String = "."

        override fun env(name: String): String? = null
    }

    private class PerRunModule(
        override val id: String,
        private val log: MutableList<String>,
        private val initOk: Boolean = true,
        private val label: String = "prototype",
    ) : Module {
        override val name: String = id
        override val version: String = "1.0"
        override val type: ModuleType = ModuleType.RULE
        override val scope: ModuleScope get() = ModuleScope.PER_RUN

        override suspend fun newHandle(context: PluginContext): Module {
            log += "spawn:$id"
            return PerRunModule(id, log, initOk, label = "handle:${context.pluginConfig}")
        }

        override suspend fun initialize(context: PluginContext): Boolean {
            log += "init:$label"
            return initOk
        }

        override suspend fun shutdown() {
            log += "shutdown:$label"
        }
    }

    private class SharedModule(
        override val id: String,
        private val log: MutableList<String>,
    ) : Module {
        override val name: String = id
        override val version: String = "1.0"
        override val type: ModuleType = ModuleType.RULE

        override suspend fun initialize(context: PluginContext): Boolean = true

        override suspend fun shutdown() {
            log += "shutdown:$id"
        }
    }

    private fun cfg(key: String) = Ctx(JsonObject(mapOf("apiKey" to JsonPrimitive(key))))

    @Test
    fun perRunHandleIsMemoizedPerConfigAndClosedOnce() =
        runTest {
            val log = mutableListOf<String>()
            val module = PerRunModule("p", log)
            val session = ModuleRegistryImpl().forRun()

            val a1 = session.handle(module, cfg("a"))
            val a2 = session.handle(module, cfg("a"))
            val b = session.handle(module, cfg("b"))

            assertSame(a1, a2)
            assertNotSame(a1, b)
            assertNotSame<Module?>(module, a1)
            assertEquals(2, log.count { it == "spawn:p" })

            session.close()
            session.close()
            assertEquals(
                listOf("shutdown:handle:{\"apiKey\":\"b\"}", "shutdown:handle:{\"apiKey\":\"a\"}"),
                log.filter { it.startsWith("shutdown") },
            )
        }

    @Test
    fun failedHandleIsMemoizedAsNullAndShutDown() =
        runTest {
            val log = mutableListOf<String>()
            val module = PerRunModule("p", log, initOk = false)
            val session = ModuleRegistryImpl().forRun()

            assertNull(session.handle(module, cfg("a")))
            assertNull(session.handle(module, cfg("a")))

            assertEquals(1, log.count { it == "spawn:p" })
            assertEquals(1, log.count { it.startsWith("shutdown") })
        }

    @Test
    fun sharedModuleIsReturnedAsIsAndTrackedModulesShutDownInReverseOrder() =
        runTest {
            val log = mutableListOf<String>()
            val shared = SharedModule("s1", log)
            val session = ModuleRegistryImpl().forRun()

            assertSame(shared, session.handle(shared, cfg("a")))
            session.track(shared)
            session.track(SharedModule("s2", log))
            session.track(shared)
            session.close()

            // re-tracking moves s1 last, so it shuts down first; each module exactly once
            assertEquals(listOf("shutdown:s1", "shutdown:s2"), log)
        }

    @Test
    fun sharedModuleShutsDownOnlyWhenTheLastOverlappingRunCloses() =
        runTest {
            val log = mutableListOf<String>()
            val shared = SharedModule("s", log)
            val registry = ModuleRegistryImpl()
            val first = registry.forRun()
            val second = registry.forRun()

            first.track(shared)
            second.track(shared)
            first.close()
            assertEquals(emptyList(), log, "a run still using the module must not see it shut down")
            second.close()
            assertEquals(listOf("shutdown:s"), log)

            // the next run leases it afresh and shuts it down again
            val third = registry.forRun()
            third.track(shared)
            third.close()
            assertEquals(listOf("shutdown:s", "shutdown:s"), log)
            assertEquals(0, SharedModuleLeases.count(shared))
        }

    @Test
    fun servicesProvidedLaterAreVisibleInsideTheRunContext() =
        runTest {
            val session = ModuleRegistryImpl().forRun()
            val key = serviceKey<String>("k")
            val sameName = serviceKey<String>("k")

            withContext(session.services) {
                assertNull(RunScopedServices.current()?.get(key))
                session.provide(key, "v")
                assertEquals("v", RunScopedServices.current()?.get(key))
                assertNull(RunScopedServices.current()?.get(sameName))
            }
            assertNull(RunScopedServices.current())
        }

    @Test
    fun concurrentSessionsDoNotSeeEachOthersServices() =
        runTest {
            val key = serviceKey<String>("run.id")
            val registry = ModuleRegistryImpl()
            val seen = coroutineScope {
                (1..8)
                    .map { i ->
                        async {
                            val session = registry.forRun()
                            withContext(session.services) {
                                session.provide(key, "run-$i")
                                kotlinx.coroutines.yield()
                                RunScopedServices.current()?.get(key)
                            }.also { session.close() }
                        }
                    }.awaitAll()
            }
            assertEquals((1..8).map { "run-$it" }, seen)
        }
}
