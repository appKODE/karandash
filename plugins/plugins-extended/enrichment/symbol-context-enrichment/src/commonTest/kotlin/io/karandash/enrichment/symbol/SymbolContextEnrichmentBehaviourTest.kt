package io.karandash.enrichment.symbol

import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.models.support.KarandashJson
import io.karandash.platform.ProcessResult
import io.karandash.sdk.enrichment.EnrichmentKeys
import io.karandash.testfixtures.TestPluginContext
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SymbolContextEnrichmentBehaviourTest {
    private class Call(
        val command: List<String>,
        val workingDir: String?,
    )

    private class FakeAstGrep(
        private val versionExit: Int = 0,
        private val failScan: Boolean = false,
        private val matchesByFile: Map<String, Map<String, String>> = emptyMap(),
    ) {
        val calls = mutableListOf<Call>()

        val runner: ProcessRunner = { command, workingDir, _ ->
            calls += Call(command, workingDir)
            when {
                command.last() == "--version" -> {
                    ProcessResult(versionExit, "ast-grep 0.30.0", "")
                }

                failScan -> {
                    throw IllegalStateException("spawn failed")
                }

                else -> {
                    val pattern = command[command.indexOf("--pattern") + 1]
                    val output = matchesByFile[command.last()]?.get(pattern)
                    if (output == null) ProcessResult(1, "", "") else ProcessResult(0, output, "")
                }
            }
        }
    }

    private fun match(
        text: String,
        name: String?,
        line: Int,
    ): String {
        val meta = if (name == null) "{}" else """{"${'$'}NAME":{"text":"$name"}}"""
        return """{"text":"$text","range":{"start":{"line":$line,"column":0}},"metaVariables":$meta}"""
    }

    private fun mr(vararg paths: String) =
        createTestMergeRequest(changes = paths.map { FileChange(path = it, changeType = ChangeType.MODIFIED) })

    private fun symbols(element: JsonElement?): List<SymbolInfo> =
        KarandashJson.Default.decodeFromJsonElement(ListSerializer(SymbolInfo.serializer()), checkNotNull(element))

    private suspend fun initialized(
        fake: FakeAstGrep,
        config: JsonElement = JsonObject(emptyMap()),
    ): SymbolContextEnrichmentProvider {
        val provider = SymbolContextEnrichmentProvider(fake.runner)
        assertTrue(provider.initialize(TestPluginContext(pluginConfig = config)))
        return provider
    }

    @Test
    fun `scan runs ast-grep run on the changed file with streamed json`() =
        runTest {
            val fake = FakeAstGrep()
            val provider = initialized(fake, JsonObject(mapOf("ast-grep-path" to JsonPrimitive("/opt/sg"))))

            provider.enrich(mr("src/A.kt"), JsonObject(emptyMap()))

            assertEquals(listOf("/opt/sg", "--version"), fake.calls.first().command)
            val scan = fake.calls[1]
            assertEquals(
                listOf("/opt/sg", "run", "--pattern", "fun \$NAME(\$\$\$) { \$\$\$ }", "--lang", "kotlin", "--json=stream", "src/A.kt"),
                scan.command,
            )
            assertEquals("/tmp", scan.workingDir)
            assertEquals(3, fake.calls.size, "one probe plus function and class scans")
        }

    @Test
    fun `symbols are attributed to the file they come from and sorted by line`() =
        runTest {
            val kotlinFun = "fun \$NAME(\$\$\$) { \$\$\$ }"
            val kotlinClass = "class \$NAME(\$\$\$) { \$\$\$ }"
            val fake =
                FakeAstGrep(
                    matchesByFile =
                        mapOf(
                            "src/A.kt" to
                                mapOf(
                                    kotlinFun to
                                        match("fun run(x: Int) {\\n  body\\n}", "run", 20) + "\n\n" + match("fun anon() {}", null, 3),
                                    kotlinClass to "not json\n{broken\n" + match("class Box(val v: Int) {", "Box", 5),
                                ),
                            "src/B.py" to mapOf("def \$NAME(\$\$\$):" to match("def go(a):", "go", 1)),
                        ),
                )
            val provider = initialized(fake)

            val result = provider.enrich(mr("src/A.kt", "src/B.py", "README.md", "src/C.go"), JsonObject(emptyMap()))

            val byFile = checkNotNull(result[EnrichmentKeys.SYMBOLS_CHANGED]).jsonObject
            assertEquals(setOf("src/A.kt", "src/B.py"), byFile.keys)
            assertEquals(
                listOf(
                    SymbolInfo(SymbolKind.CLASS, "Box", "class Box(val v: Int) {", 5),
                    SymbolInfo(SymbolKind.FUNCTION, "run", "fun run(x: Int) {", 20),
                ),
                symbols(byFile["src/A.kt"]),
            )
            assertEquals(listOf(SymbolInfo(SymbolKind.FUNCTION, "go", "def go(a):", 1)), symbols(byFile["src/B.py"]))
            assertTrue(fake.calls.none { it.command.last() == "README.md" }, "unsupported files are not scanned")
        }

    @Test
    fun `no matches anywhere gives an empty map`() =
        runTest {
            val provider = initialized(FakeAstGrep())

            assertTrue(provider.enrich(mr("src/A.ts", "src/b.js"), JsonObject(emptyMap())).isEmpty())
            assertTrue(provider.enrich(mr(), JsonObject(emptyMap())).isEmpty())
            assertTrue(provider.enrich(mr(" "), JsonObject(emptyMap())).isEmpty())
        }

    @Test
    fun `scan failure degrades to no symbols`() =
        runTest {
            val context = TestPluginContext()
            val provider = SymbolContextEnrichmentProvider(FakeAstGrep(failScan = true).runner)
            assertTrue(provider.initialize(context))

            assertTrue(provider.enrich(mr("src/A.kt"), JsonObject(emptyMap())).isEmpty())
            assertEquals(2, context.warnings.count { "ast-grep: scan failed for kotlin: spawn failed" in it }, context.warnings.toString())
        }

    @Test
    fun `failing version probe disables the provider`() =
        runTest {
            val fake = FakeAstGrep(versionExit = 2)
            val provider = SymbolContextEnrichmentProvider(fake.runner)

            assertFalse(provider.initialize(TestPluginContext()))
            assertTrue(provider.skipReason().orEmpty().contains("'ast-grep'"), provider.skipReason())
            assertTrue(provider.enrich(mr("src/A.kt"), JsonObject(emptyMap())).isEmpty())
            assertEquals(1, fake.calls.size)
        }

    @Test
    fun `shutdown stops enrichment`() =
        runTest {
            val provider = initialized(FakeAstGrep())
            provider.shutdown()

            assertTrue(provider.enrich(mr("src/A.kt"), JsonObject(emptyMap())).isEmpty())
        }

    @Test
    fun `invalid config falls back to defaults with a warning`() =
        runTest {
            val fake = FakeAstGrep()
            val context = TestPluginContext(pluginConfig = JsonObject(mapOf("changed" to JsonPrimitive("yes"))))
            val provider = SymbolContextEnrichmentProvider(fake.runner)

            assertTrue(provider.initialize(context))
            assertEquals(listOf("ast-grep", "--version"), fake.calls.single().command)
            assertTrue(context.warnings.any { "invalid config, using defaults" in it }, context.warnings.toString())
        }
}
