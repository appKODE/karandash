package io.karandash.bootstrap

import io.karandash.api.platform.PatternDefinition
import io.karandash.api.platform.PatternRegistry
import io.karandash.patterns.PatternResolver
import io.karandash.sdk.Logger
import io.karandash.testfixtures.TestFiles
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class BootstrapContextTest {
    private class Lines : Logger {
        val lines = mutableListOf<String>()
        override val isDebugEnabled = true

        override fun debug(message: String) {
            lines += "debug $message"
        }

        override fun info(message: String) {
            lines += "info $message"
        }

        override fun warn(message: String) {
            lines += "warn $message"
        }

        override fun error(message: String) {
            lines += "error $message"
        }

        override fun error(
            message: String,
            throwable: Throwable,
        ) = error(message)

        override fun withContext(
            key: String,
            value: String,
        ): Logger = this
    }

    private object NoPatterns : PatternRegistry {
        override fun resolve(
            name: String,
            params: Map<String, String>,
        ): String? = null

        override fun exists(name: String) = false

        override fun register(definition: PatternDefinition) = Unit

        override fun all(): List<PatternDefinition> = emptyList()
    }

    @Test
    fun `patterns match literally with a warning until a resolver is set or when they fail to compile`() {
        val log = Lines()
        val bootstrap = PluginBootstrap(logger = DelegatingLogger(log))
        val before = bootstrap.forModule("m").resolvePattern("a+")

        bootstrap.patternResolver = PatternResolver(NoPatterns)
        val compiled = bootstrap.forModule("m").resolvePattern("regex:^a+$")
        val broken = bootstrap.forModule("m").resolvePattern("regex:[x")

        assertTrue(before.matches("a+"))
        assertFalse(before.matches("aa"))
        assertTrue(compiled.matches("aaa"))
        assertTrue(broken.matches("regex:[x"))
        assertTrue(log.lines.any { it.startsWith("warn Pattern resolver not ready yet") }, log.lines.toString())
        assertTrue(log.lines.any { it.startsWith("warn Pattern compilation failed for 'regex:[x'") }, log.lines.toString())
    }

    /** The host lists its whole named-pattern table for the bridged guests. */
    @Test
    fun `the host context lists every named pattern its registry compiles`() {
        val bootstrap = PluginBootstrap()
        assertEquals(emptyList(), bootstrap.forModule("m").namedPatternNames())

        bootstrap.patternResolver = PatternResolver(TwoPatterns)

        assertEquals(listOf("@ticket"), bootstrap.forModule("m").namedPatternNames())
    }

    /** A user `@ticket` pattern and a `@broken` one whose expansion does not compile. */
    private object TwoPatterns : PatternRegistry {
        private val all = listOf(PatternDefinition("ticket") { "[A-Z]+-[0-9]+" }, PatternDefinition("broken") { "regex:[x" })

        override fun resolve(
            name: String,
            params: Map<String, String>,
        ): String? = all.find { it.name == name }?.expand?.invoke(params)

        override fun exists(name: String) = all.any { it.name == name }

        override fun register(definition: PatternDefinition) = Unit

        override fun all(): List<PatternDefinition> = all
    }

    @Test
    fun `format registry is unavailable before plugins load`() {
        assertFailsWith<IllegalStateException> { PluginBootstrap().formatRegistry }
    }

    @Test
    fun `loading from dirs logs each external load decision and shuts down`() =
        runTest {
            val dir = TestFiles.createTestTempDirectory("bootstrap-ctx")
            SystemFileSystem.createDirectories(dir)
            val plugin = Path(dir, "broken.mjs")
            SystemFileSystem.sink(plugin).buffered().use { it.writeString("not ( javascript") }
            try {
                val log = Lines()
                val bootstrap = PluginBootstrap(logger = DelegatingLogger(log))

                val result = bootstrap.loadPluginsFromDirs(listOf(dir, dir))

                assertTrue(result.failed.any { "broken.mjs" in it.path }, result.failed.toString())
                assertTrue(log.lines.any { it.startsWith("debug [js] load") && "failed" in it }, log.lines.toString())
                assertEquals(bootstrap.registry(), bootstrap.registry())
                bootstrap.formatRegistry
                bootstrap.configureShutdownTimeout(1.seconds)
                bootstrap.shutdown()
            } finally {
                SystemFileSystem.delete(plugin, mustExist = false)
                SystemFileSystem.delete(dir, mustExist = false)
            }
        }
}
