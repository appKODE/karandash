package io.karandash.detekt

import dev.detekt.test.TestConfig
import dev.detekt.test.lint
import kotlin.test.Test
import kotlin.test.assertEquals

class OtherRulesTest {
    @Test
    fun `raw console output is reported, Mordant and Logger calls are not`() {
        val rule = RawConsoleOutput(TestConfig())
        val bad =
            """
            fun a() {
                println("x")
                print("y")
                System.err.println("z")
                java.lang.System.out.flush()
            }
            """.trimIndent()
        assertEquals(4, rule.lint(bad).size)
        assertEquals(0, rule.lint("fun a() { terminal.println(\"x\"); logger.info(\"y\"); out.print(1) }").size)
    }

    @Test
    fun `raw SystemTemporaryDirectory is reported, the import and TestFiles are not`() {
        val rule = RawTestTempDirectory(TestConfig())
        val bad =
            """
            import kotlinx.io.files.SystemTemporaryDirectory
            val a = Path(SystemTemporaryDirectory, "x")
            val b = SystemTemporaryDirectory
            """.trimIndent()
        assertEquals(2, rule.lint(bad).size)
        assertEquals(0, rule.lint("val a = TestFiles.createTestTempDirectory(\"x\")").size)
    }

    @Test
    fun `raw createTemp calls and tmp literals are reported`() {
        val rule = RawTestTempDirectory(TestConfig())
        val bad =
            """
            import java.nio.file.Files
            import kotlin.io.path.createTempDirectory
            val a = Files.createTempDirectory("x")
            val b = Files.createTempFile("x", ".txt")
            val c = createTempDirectory("y")
            val d = kotlin.io.path.createTempFile()
            val e = Path("/tmp/karandash")
            val f = File("/tmp")
            val g = Path("${'$'}{root}/tmp/x")
            """.trimIndent()
        assertEquals(7, rule.lint(bad).size)
        val good =
            """
            import kotlin.io.path.createTempDirectory
            val a = TestFiles.createTestTempDirectory("x")
            val b = TestFiles.writeText(Path(dir, "a.txt"), "x")
            val c = "build/tmp/x"
            val d = "/private/tmp-like"
            val e = "see /tmpfile"
            val f = Path("/private/tmp-like")
            val g = WorkingDirectory("/tmp/test")
            val h = isAbsolutePath("/tmp/workspace")
            """.trimIndent()
        assertEquals(0, rule.lint(good).size)
    }

    @Test
    fun `untyped map is reported unless marked parser-boundary`() {
        val rule = UntypedConfigMap(TestConfig())
        val bad =
            """
            fun a(m: Map<String, Any?>): List<MutableMap<String,Any?>> = emptyList()
            val b = emptyMap<String, Any?>()
            """.trimIndent()
        assertEquals(3, rule.lint(bad).size)
        val good =
            """
            // parser-boundary: YAML parser output
            fun a(m: Map<String, Any?>) = m
            fun b(m: Map<String, Any>, n: Map<String, JsonElement>) = m
            val c = mapOf<String, Any?>() // lowercase builders are not types
            val d: Map<String, Any?> = emptyMap() // parser-boundary: same line
            """.trimIndent()
        assertEquals(0, rule.lint(good).size)
    }

    @Test
    fun `suppress ids outside the allowed list are reported`() {
        val rule = SuppressOutsidePolicy(TestConfig("allowedIds" to listOf("UNCHECKED_CAST", "unused")))
        val code =
            """
            @file:Suppress("MagicNumber")
            @Suppress("UNCHECKED_CAST", "LongMethod")
            fun a() = 1
            @Suppress(names = ["unused"])
            fun b() = 2
            """.trimIndent()
        assertEquals(
            listOf("MagicNumber", "LongMethod"),
            rule.lint(code).map { it.message.substringAfter("(\"").substringBefore("\")") },
        )
    }

    @Test
    fun `the rule cannot be suppressed by its own id`() {
        val rule = SuppressOutsidePolicy(TestConfig("allowedIds" to listOf("unused")))
        val declaration =
            """
            @Suppress("SuppressOutsidePolicy", "LongMethod")
            fun a() = 1
            @SuppressWarnings("karandash:SuppressOutsidePolicy")
            fun b() = 2
            @Suppress("all")
            fun c() = 3
            """.trimIndent()
        assertEquals(
            listOf("SuppressOutsidePolicy", "LongMethod", "karandash:SuppressOutsidePolicy", "all"),
            rule.lint(declaration).map { it.message.substringAfter("(\"").substringBefore("\")") },
        )
        val file =
            """
            @file:Suppress("karandash")
            @Suppress("MagicNumber")
            fun a() = 1
            """.trimIndent()
        assertEquals(2, rule.lint(file).size)
    }

    @Test
    fun `provider exposes every rule`() {
        val rules = KarandashRuleSetProvider().instance().rules
        assertEquals(14, rules.size)
    }
}
