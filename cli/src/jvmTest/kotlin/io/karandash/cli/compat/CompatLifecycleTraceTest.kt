package io.karandash.cli.compat

import com.github.ajalt.clikt.testing.test
import io.karandash.api.Module
import io.karandash.api.ModuleRegistry
import io.karandash.api.ModuleRegistryImpl
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cli.commands.validate.ValidateCommand
import io.karandash.sdk.Logger
import kotlinx.coroutines.test.runTest
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.Collections
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes

/**
 * Freezes the module lifecycle of one `validate` run (file mode, `compat/output/configs/failing.yaml`):
 *
 * - bootstrap: which built-in modules are initialized eagerly and which are deferred (sorted: modules
 *   load and initialize concurrently, so only the set of lines is stable, not their interleaving)
 *   (from the bootstrap loader's debug log);
 * - run: every `initialize` / `shutdown` call that reaches a module of any type through the [ModuleRegistry],
 *   one line per module (sorted by module id) with that module's calls in the order they happened, e.g.
 *   `io.karandash.format.json: initialize/1 -> shutdown/0`. Modules run concurrently, so only the per-module
 *   order is stable; a module initialized twice or shut down before init shows up in its line.
 *
 * Calls are observed by swapping each registered module for a JDK proxy (same interfaces, every call forwarded)
 * after bootstrap. The registry itself is a proxy that exposes only [ModuleRegistry], so a CLI cast to the concrete
 * registry class fails this test. Known gap, needing a
 * production hook to close:
 * - `PluginBootstrap.shutdown()` shuts modules down through the loader's own references, so loader-level
 *   shutdown is not in the trace.
 */
class CompatLifecycleTraceTest {
    @Test
    fun `module init and shutdown order stays the same`() =
        runTest(timeout = 5.minutes) {
            val bootstrapLog = Collections.synchronizedList(mutableListOf<String>())
            val calls = Collections.synchronizedList(mutableListOf<String>())
            val bootstrap = PluginBootstrap(registry = interfaceOnly(ModuleRegistryImpl()), envReader = { null })
            bootstrap.logger.configure(DebugCapture(bootstrapLog))
            bootstrap.loadPluginsFromDirs(emptyList())
            traceModules(bootstrap.registry(), calls)
            val (result, _, _) = ConsoleCapture.capture {
                ValidateCommand(bootstrap).test(
                    listOf("--no-auto-detect", "--no-cache", "--config", CONFIG, "--mr", MR, "--format", "json"),
                )
            }
            bootstrap.shutdown()

            val trace = buildString {
                appendLine("exit=${result.statusCode}")
                appendLine("## bootstrap")
                bootstrapLog.filter { it.startsWith("Module '") }.sorted().forEach { appendLine(it) }
                appendLine("## run")
                calls
                    .groupBy({ it.substringAfter(' ') }, { it.substringBefore(' ') })
                    .toSortedMap()
                    .forEach { (id, sequence) -> appendLine("$id: ${sequence.joinToString(" -> ")}") }
            }
            Golden.assertAllMatch(listOfNotNull(Golden.check("output/lifecycle-trace.txt", trace)))
        }

    private suspend fun traceModules(
        registry: ModuleRegistry,
        calls: MutableList<String>,
    ) {
        registry.registeredIds().sorted().mapNotNull(registry::get).forEach { module ->
            registry.unregister(module.id)
            registry.register(traced(module, calls))
        }
    }

    private fun traced(
        module: Module,
        calls: MutableList<String>,
    ): Module =
        Proxy.newProxyInstance(Module::class.java.classLoader, interfacesOf(module.javaClass).toTypedArray()) { _, method, args ->
            if (method.name == "initialize" || method.name == "shutdown") {
                // the trailing argument of a suspend function is its Continuation
                val arity = (args?.size ?: 1) - 1
                calls += "${method.name}/$arity ${module.id}"
            }
            try {
                method.invoke(module, *(args ?: emptyArray()))
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        } as Module

    private fun interfaceOnly(registry: ModuleRegistry): ModuleRegistry =
        Proxy.newProxyInstance(ModuleRegistry::class.java.classLoader, arrayOf(ModuleRegistry::class.java)) { _, method, args ->
            try {
                method.invoke(registry, *(args ?: emptyArray()))
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        } as ModuleRegistry

    private fun interfacesOf(type: Class<*>): Set<Class<*>> =
        generateSequence(type) { it.superclass }
            .flatMap { it.interfaces.asSequence() }
            .flatMap { sequenceOf(it) + interfacesOf(it) }
            .toSet()

    private class DebugCapture(
        private val lines: MutableList<String>,
    ) : Logger {
        override val isDebugEnabled: Boolean get() = true

        override fun debug(message: String) {
            lines += message
        }

        override fun info(message: String) = Unit

        override fun warn(message: String) = Unit

        override fun error(message: String) = Unit
    }

    private companion object {
        const val MR = "tools/integration-tests/fixtures/test-mr.json"
        const val CONFIG = "${Golden.ROOT}/output/configs/failing.yaml"
    }
}
