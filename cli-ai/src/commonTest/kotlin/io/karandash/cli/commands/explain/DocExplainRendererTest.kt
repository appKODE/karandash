package io.karandash.cli.commands.explain

import io.karandash.api.config.ConfigProperty
import io.karandash.api.config.ConfigPropertyType
import io.karandash.patterns.PatternDescriber
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DocExplainRendererTest {
    private fun emptyResult() =
        ExplainResult(
            configPath = null,
            configuredModules = emptyList(),
            availableModules = emptyList(),
            globalIssues = emptyList(),
        )

    private fun moduleReport(
        id: String,
        type: String = "Rule",
        description: String = "Test description",
        fields: List<FieldReport> = emptyList(),
        isConfigured: Boolean = true,
        isDefaultEnabled: Boolean = false,
        unknownKeys: List<UnknownKeyReport> = emptyList(),
        missingRequired: List<ConfigProperty> = emptyList(),
    ) = ModuleReport(
        moduleId = id,
        moduleType = type,
        description = description,
        isConfigured = isConfigured,
        isDefaultEnabled = isDefaultEnabled,
        fields = fields,
        unknownKeys = unknownKeys,
        missingRequired = missingRequired,
    )

    private fun fieldReport(
        name: String,
        type: ConfigPropertyType = ConfigPropertyType.STRING,
        value: Any? = null,
        isSet: Boolean = value != null,
        isValid: Boolean = true,
        required: Boolean = false,
        defaultValue: Any? = null,
        enumValues: List<String>? = null,
        deprecated: String? = null,
    ) = FieldReport(
        property = ConfigProperty(
            name = name,
            type = type,
            description = "Test field",
            required = required,
            defaultValue = defaultValue,
            enumValues = enumValues,
            deprecated = deprecated,
        ),
        actualValue = value,
        isSet = isSet,
        isValid = isValid,
    )

    @Test
    fun `render empty config produces minimal doc`() {
        val result = emptyResult()
        val output = DocExplainRenderer.render(result)

        assertContains(output, "Validation Rules Documentation")
        assertContains(output, "zero-config defaults")
        assertContains(output, "0 active rule(s)")
    }

    @Test
    fun `render shows config path when provided`() {
        val result = emptyResult().copy(configPath = "config.yaml")
        val output = DocExplainRenderer.render(result)

        assertContains(output, "`config.yaml`")
        assertFalse(output.contains("zero-config defaults"))
    }

    @Test
    fun `render single rule with fields produces config table`() {
        val report = moduleReport(
            id = "title-format",
            fields = listOf(
                fieldReport("pattern", value = "^(feat|fix):"),
                fieldReport("minLength", type = ConfigPropertyType.INT, value = 10),
            ),
        )
        val result = emptyResult().copy(configuredModules = listOf(report))
        val output = DocExplainRenderer.render(result)

        assertContains(output, "### Title Format")
        assertContains(output, "| Property | Type | Value | Default | Required |")
        assertContains(output, "`pattern`")
        assertContains(output, "`^(feat|fix):`")
        assertContains(output, "`minLength`")
    }

    @Test
    fun `extract constraint from pattern field`() {
        val report = moduleReport(
            id = "title-format",
            fields = listOf(
                fieldReport("pattern", value = "^(feat|fix):"),
            ),
        )
        val constraints = DocExplainRenderer.extractConstraints(report)

        assertTrue(constraints.any { it.contains("Must match regex") && it.contains("^(feat|fix):") })
    }

    @Test
    fun `a pattern constraint shows an example`() {
        val report = moduleReport(id = "title-format", fields = listOf(fieldReport("pattern", value = "^(feat|fix):")))

        assertEquals(
            listOf("Must match regex: `^(feat|fix):` (e.g. `feat: ...`)"),
            DocExplainRenderer.extractConstraints(report).map { it.substringBefore(" — ") },
        )
    }

    @Test
    fun `a pomsky pattern constraint shows the author test example`() {
        val pattern = "pomsky:test { match 'fix: crash'; } ('feat' | 'fix') ': ' .+"
        val report = moduleReport(id = "title-format", fields = listOf(fieldReport("pattern", value = pattern)))

        val constraint = DocExplainRenderer.extractConstraints(report).single()
        assertTrue(constraint.contains("(e.g. `fix: crash`)"), constraint)
    }

    @Test
    fun `a pattern with no example keeps the plain constraint`() {
        val report = moduleReport(id = "title-format", fields = listOf(fieldReport("pattern", value = "[a-z]+")))

        assertEquals(listOf("Must match regex: `[a-z]+`"), DocExplainRenderer.extractConstraints(report).map { it.substringBefore(" — ") })
    }

    @Test
    fun `a pattern constraint carries the plain-English explanation`() {
        val explanation = checkNotNull(PatternDescriber.explain("[a-z]+"))
        val report = moduleReport(id = "title-format", fields = listOf(fieldReport("pattern", value = "[a-z]+")))

        assertEquals(listOf("Must match regex: `[a-z]+` — $explanation"), DocExplainRenderer.extractConstraints(report))
    }

    @Test
    fun `extract constraint from min and max fields`() {
        val report = moduleReport(
            id = "size-limits",
            fields = listOf(
                fieldReport("minApprovals", type = ConfigPropertyType.INT, value = 2),
                fieldReport("maxFiles", type = ConfigPropertyType.INT, value = 50),
            ),
        )
        val constraints = DocExplainRenderer.extractConstraints(report)

        assertTrue(constraints.any { it.startsWith("Minimum") })
        assertTrue(constraints.any { it.startsWith("Maximum") })
    }

    @Test
    fun `extract constraint from enum field`() {
        val report = moduleReport(
            id = "test-rule",
            fields = listOf(
                fieldReport(
                    "severity",
                    type = ConfigPropertyType.ENUM,
                    value = "warning",
                    enumValues = listOf("error", "warning", "info"),
                ),
            ),
        )
        val constraints = DocExplainRenderer.extractConstraints(report)

        assertTrue(constraints.any { it.contains("must be one of") && it.contains("`error`") })
    }

    @Test
    fun `extract constraint from allowed string list`() {
        val report = moduleReport(
            id = "labels",
            fields = listOf(
                fieldReport(
                    "allowed-labels",
                    type = ConfigPropertyType.STRING_LIST,
                    value = listOf("bug", "feature", "docs"),
                ),
            ),
        )
        val constraints = DocExplainRenderer.extractConstraints(report)

        assertTrue(constraints.any { it.contains("Allowed values") && it.contains("`bug`") })
    }

    @Test
    fun `extract constraint for required missing field`() {
        val report = moduleReport(
            id = "test-rule",
            fields = listOf(
                fieldReport("token", required = true, isSet = false),
            ),
        )
        val constraints = DocExplainRenderer.extractConstraints(report)

        assertTrue(constraints.any { it.contains("required but not configured") })
    }

    @Test
    fun `extract constraint for deprecated field`() {
        val report = moduleReport(
            id = "test-rule",
            fields = listOf(
                fieldReport("oldField", deprecated = "Use newField instead"),
            ),
        )
        val constraints = DocExplainRenderer.extractConstraints(report)

        assertTrue(constraints.any { it.contains("deprecated") && it.contains("Use newField instead") })
    }

    @Test
    fun `render generates checklist from constraints`() {
        val report = moduleReport(
            id = "title-format",
            fields = listOf(
                fieldReport("pattern", value = "^feat:"),
                fieldReport("maxLength", type = ConfigPropertyType.INT, value = 72),
            ),
        )
        val result = emptyResult().copy(configuredModules = listOf(report))
        val output = DocExplainRenderer.render(result)

        assertContains(output, "## Quick Reference Checklist")
        assertContains(output, "- [ ]")
    }

    @Test
    fun `render includes available modules section`() {
        val available = moduleReport(
            id = "security",
            description = "Security pattern detection",
            isConfigured = false,
            isDefaultEnabled = false,
        )
        val result = emptyResult().copy(availableModules = listOf(available))
        val output = DocExplainRenderer.render(result)

        assertContains(output, "## Available Modules (Not Configured)")
        assertContains(output, "`security`")
        assertContains(output, "Security pattern detection")
    }

    @Test
    fun `render includes zero-config default modules as active`() {
        val defaultModule = moduleReport(
            id = "draft",
            description = "Draft MR detection",
            isConfigured = false,
            isDefaultEnabled = true,
        )
        val result = emptyResult().copy(availableModules = listOf(defaultModule))
        val output = DocExplainRenderer.render(result)

        assertContains(output, "### Draft")
        assertContains(output, "1 active rule(s)")
    }

    @Test
    fun `render multiple rules preserves order`() {
        val modules = listOf(
            moduleReport(id = "title-format"),
            moduleReport(id = "commit-messages"),
            moduleReport(id = "branch-naming"),
        )
        val result = emptyResult().copy(configuredModules = modules)
        val output = DocExplainRenderer.render(result)

        val titlePos = output.indexOf("### Title Format")
        val commitPos = output.indexOf("### Commit Messages")
        val branchPos = output.indexOf("### Branch Naming")

        assertTrue(titlePos < commitPos)
        assertTrue(commitPos < branchPos)
    }
}
