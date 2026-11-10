package io.karandash.detekt

import dev.detekt.test.TestConfig
import dev.detekt.test.lint
import dev.detekt.test.utils.compileForTest
import org.jetbrains.kotlin.psi.KtFile
import kotlin.io.path.createParentDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

class CallRulesTest {
    @Test
    fun `forbidden calls are reported, lookalikes are not`() {
        val cases =
            mapOf(
                ForbiddenRunCatching(TestConfig()) to ("val a = runCatching { 1 }\nval b = 1.runCatching { 2 }" to 2),
                ForbiddenWithTimeout(TestConfig()) to ("val a = withTimeout(1) { }\nval b = withTimeoutOrNull(1) { }" to 1),
                DirectHttpClient(TestConfig()) to ("val a = HttpClient(CIO) { }\nval b = ProviderHttpClientFactory.create()" to 1),
                DirectGetenv(TestConfig()) to ("val a = System.getenv(\"A\")\nval b = PlatformEnvironment.getEnv(\"A\")" to 1),
            )
        cases.forEach { (rule, case) -> assertEquals(case.second, rule.lint(case.first).size, rule.ruleName.value) }
    }

    @Test
    fun `inline Json builder is reported unless marked standalone-plugin`() {
        val rule = InlineJsonBuilder(TestConfig())
        assertEquals(2, rule.lint("val a = Json { }\nval b = Json(KarandashJson.Default) { }").size)
        val marked =
            """
            // standalone-plugin: links no karandash library
            val a = Json { }
            val b = Json { } // standalone-plugin: same
            val c = KarandashJson.Default
            """.trimIndent()
        assertEquals(0, rule.lint(marked).size)
    }

    @Test
    fun `only an EmptyCoroutineContext scope is reported`() {
        val rule = UnownedCoroutineScope(TestConfig())
        val code =
            """
            val a = CoroutineScope(EmptyCoroutineContext)
            val b = CoroutineScope(kotlin.coroutines.EmptyCoroutineContext)
            val c = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            """.trimIndent()
        assertEquals(2, rule.lint(code).size)
    }

    @Test
    fun `runBlocking needs a sanctioned file and a bridge-boundary comment`() {
        val rule = UnsanctionedRunBlocking(TestConfig("sanctionedFiles" to listOf("cli/src/commonMain/Cli.kt")))
        val code =
            """
            fun a() {
                // bridge-boundary: CLI entry
                runBlocking { }
            }
            fun b() = runBlocking {
                // bridge-boundary: first line of the block
            }


            fun c() = runBlocking { }
            """.trimIndent()
        val sanctioned = rule.lint(fileAt("cli/src/commonMain/Cli.kt", code))
        assertEquals(listOf("runBlocking without // bridge-boundary: <reason>."), sanctioned.map { it.message })
        assertEquals(3, rule.lint(fileAt("core/src/commonMain/Other.kt", code)).size)
    }

    // detekt 2's in-memory test files keep only the file name, so the path rule needs a real file on disk.
    private fun fileAt(
        relativePath: String,
        content: String,
    ): KtFile {
        val path = createTempDirectory("repo").resolve(relativePath)
        path.createParentDirectories().writeText(content)
        return compileForTest(path)
    }
}
