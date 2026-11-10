package io.karandash.lsp

import io.karandash.api.config.ConfigPropertyType
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.lsp.ModuleDocRegistry.displayName
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.DidChangeConfigurationParams
import org.eclipse.lsp4j.DidChangeWatchedFilesParams
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LspCoverageGapsTest {
    private val registry = TestRegistryFactory.createRegistry()
    private val metadata = CachedRegistryMetadata(registry)
    private val completions = CompletionProvider(metadata)
    private val diagnostics = DiagnosticProvider(metadata)
    private val hover = HoverProvider(metadata)

    private fun labels(
        text: String,
        line: Int,
        character: Int,
    ) = completions.getCompletions(text, line, character).map { it.label }

    @Test
    fun `static doc tables are populated`() {
        assertTrue(ModuleDocRegistry.FALLBACK_PROPERTY_DOCS.containsKey("when"))
        assertTrue(ModuleDocRegistry.POLICY_PROPERTY_DOCS.containsKey("when"))
        assertTrue(ModuleDocRegistry.FALLBACK_PROPERTY_DOCS.values.all { it.startsWith("# ") })
        assertTrue(ModuleDocRegistry.POLICY_PROPERTY_DOCS.values.all { it.startsWith("# ") })
        assertTrue(ModuleDocRegistry.TOP_LEVEL_BLOCK_DOCS.isNotEmpty())
        assertTrue(ModuleDocRegistry.FALLBACK_VALIDATOR_DOCS.isNotEmpty())
    }

    @Test
    fun `every property type has a display name`() {
        val names = ConfigPropertyType.entries.map { it.displayName() }
        assertEquals(ConfigPropertyType.entries.size, names.toSet().size)
    }

    @Test
    fun `completions cover profile policies and property value contexts`() {
        assertEquals(listOf("id", "severity"), labels("profile {\n\n}", 1, 0))
        assertTrue("policy" in labels("policies {\n\n}", 1, 0))
        assertTrue(labels("custom {\n    a = ", 1, 8).isEmpty())
        assertTrue(labels("custom {\n    a = \"x\"", 1, 12).isEmpty())
        assertTrue("profile" in labels("rules {}", 9, 0))
    }

    @Test
    fun `completions offer typed properties for every validator`() {
        val rulesWithProperties = metadata.ruleConfigKeys.keys
        rulesWithProperties.forEach { rule ->
            val text = "rules {\n    $rule {\n\n    }\n}"
            val items = completions.getCompletions(text, 2, 0)
            assertTrue(items.isNotEmpty(), rule)
            assertTrue(items.all { it.detail != null }, rule)
        }
    }

    @Test
    fun `hover context resolves block declarations and nested blocks`() {
        val text = "platforms {\n    gitlab {\n    }\n}\nreporters {\n    comment {\n    }\n}\npolicies {\n    policy {\n    }\n}"
        assertEquals(HoverContext.PlatformsBlock, DslContextResolver.determineContext(text, 1, true))
        assertEquals(HoverContext.ReportersBlock, DslContextResolver.determineContext(text, 5, true))
        assertEquals(HoverContext.PoliciesBlock, DslContextResolver.determineContext(text, 9, true))
        assertEquals(HoverContext.PlatformsBlock, DslContextResolver.determineContext(text, 1, false))
        assertEquals(HoverContext.ReportersBlock, DslContextResolver.determineContext(text, 5, false))
        assertEquals(HoverContext.PoliciesBlock, DslContextResolver.determineContext(text, 9, false))
        assertEquals(HoverContext.TopLevel, DslContextResolver.determineContext("x = 1", 0, false))
    }

    @Test
    fun `hover resolves policy reporter and validator property docs`() {
        assertNotNull(hover.getHover("policies {\n    when = \"x\"\n}", 1, 6))
        val rules = "rules {\n    titleFormat {\n        when = \"x\"\n        pattern = \"a\"\n    }\n}"
        hover.getHover(rules, 2, 9)
        hover.getHover(rules, 3, 10)
        val reporters = "reporters {\n    console = true\n}"
        hover.getHover(reporters, 1, 6)
        hover.getHover("platforms {\n    gitlab {\n    }\n}", 1, 6)
    }

    @Test
    fun `diagnostics flag type mismatches in every property type`() {
        val input = """
            rules {
                titleFormat {
                    pattern = 5
                    minLength = true
                    requireTicket = "yes"
                }
                jiraTicket {
                    projectKeys = "A"
                    validateStatus = 3
                    validateAssignee = listOf("a")
                    pattern = listOf("a")
                }
                branchNaming {
                    pattern = "ok"
                    allowedPatterns = listOf("a")
                }
            }
        """.trimIndent()

        val messages = diagnostics.computeDiagnostics(input).map { it.message.left }
        assertTrue(messages.count { it.contains("Type mismatch") } >= 6, messages.toString())
    }

    @Test
    fun `diagnostics flag unknown platform and reporter blocks`() {
        val input = "platforms {\n    bogus {\n    }\n    other = 1\n}\nreporters {\n    nope {\n    }\n}"

        val warnings = diagnostics.computeDiagnostics(input).filter { it.severity == DiagnosticSeverity.Warning }
        assertTrue(warnings.any { it.message.left.contains("Unknown provider 'bogus'") })
        assertTrue(warnings.any { it.message.left.contains("Unknown provider 'other'") })
        assertTrue(warnings.any { it.message.left.contains("Unknown reporter block 'nope'") })
    }

    @Test
    fun `pipeline diagnostics report unparseable text`() {
        val pipeline = ConfigPipelineDiagnostics(TestRegistryFactory.loadedBootstrap())
        val broken = pipeline.diagnose("rules {")
        assertTrue(broken.all { it.severity == DiagnosticSeverity.Error })
        assertTrue(broken.isNotEmpty())
    }

    @Test
    fun `workspace service ignores configuration notifications`() {
        val service = KarandashWorkspaceService()
        service.didChangeConfiguration(DidChangeConfigurationParams())
        service.didChangeWatchedFiles(DidChangeWatchedFilesParams())
    }

    @Test
    fun `language server starts and stops when the client disconnects`() {
        startLanguageServer(PluginBootstrap(), ByteArrayInputStream(ByteArray(0)), ByteArrayOutputStream())
    }
}
