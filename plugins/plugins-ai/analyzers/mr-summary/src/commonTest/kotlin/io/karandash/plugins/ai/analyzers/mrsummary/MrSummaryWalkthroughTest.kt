package io.karandash.plugins.ai.analyzers.mrsummary

import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.models.support.WellKnownMetadataKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MrSummaryWalkthroughTest {
    private val module = MrSummaryAnalyzerModule()

    private val changes = listOf(
        FileChange("app/src/main/Login.kt", ChangeType.MODIFIED, additions = 10, deletions = 2),
        FileChange("app/src/test/LoginTest.kt", ChangeType.ADDED, additions = 5),
        FileChange("core/src/Parser.kt", ChangeType.MODIFIED, additions = 1, deletions = 1),
    )

    private fun response(extra: String = "") = """{"summary": "Adds login.", "keyChanges": ["Login screen"]$extra}"""

    @Test
    fun `walkthrough table lists each review unit with its files and line counts`() {
        val summary = module.parseResponse(response(), "m", 1, changes).single()
        val walkthrough = summary.metadata.getValue(WellKnownMetadataKeys.WALKTHROUGH)

        assertTrue(walkthrough.startsWith("| Unit | Files | Lines |\n|---|---|---|\n"), walkthrough)
        assertTrue("| `app` | `src/main/Login.kt`<br>`src/test/LoginTest.kt` | +15/-2 |" in walkthrough, walkthrough)
        assertTrue("| `core` | `src/Parser.kt` | +1/-1 |" in walkthrough, walkthrough)
        assertFalse("sequenceDiagram" in walkthrough)
    }

    @Test
    fun `no changes means no walkthrough`() {
        val summary = module.parseResponse(response(), "m", 1).single()

        assertNull(summary.metadata[WellKnownMetadataKeys.WALKTHROUGH])
    }

    @Test
    fun `sequence diagram appears when two units interact and drops self calls and unsafe text`() {
        val interactions = """, "interactions": [
            {"from": "app", "to": "core", "message": "parse(token); then #render"},
            {"from": "app", "to": "app", "message": "self call"}
        ]"""
        val walkthrough = module
            .parseResponse(response(interactions), "m", 1, changes)
            .single()
            .metadata
            .getValue(WellKnownMetadataKeys.WALKTHROUGH)

        assertTrue("```mermaid\nsequenceDiagram\n" in walkthrough, walkthrough)
        assertTrue("  participant u0 as app\n  participant u1 as core\n" in walkthrough, walkthrough)
        assertTrue("  u0->>u1: parse token then render\n" in walkthrough, walkthrough)
        assertFalse("self call" in walkthrough)
    }

    @Test
    fun `interactions inside one unit give no sequence diagram`() {
        val interactions = """, "interactions": [{"from": "app", "to": "app", "message": "self call"}]"""
        val walkthrough = module
            .parseResponse(response(interactions), "m", 1, changes)
            .single()
            .metadata
            .getValue(WellKnownMetadataKeys.WALKTHROUGH)

        assertFalse("sequenceDiagram" in walkthrough)
    }

    @Test
    fun `a suggested title and description become a generated description finding`() {
        val suggestion = """, "suggestedTitle": "Add login", "suggestedDescription": "## What\nLogin""""
        val findings = module.parseResponse(response(suggestion), "m", 7, changes)

        assertEquals(2, findings.size)
        val described = findings.single { it.metadata[WellKnownMetadataKeys.IS_GENERATED_DESCRIPTION] == "true" }
        assertEquals("mr-summary", described.rule)
        assertEquals("Add login", described.metadata[WellKnownMetadataKeys.SUGGESTED_TITLE])
        assertEquals("## What\nLogin", described.metadata[WellKnownMetadataKeys.SUGGESTED_DESCRIPTION])
        assertNull(findings.first().metadata[WellKnownMetadataKeys.SUGGESTED_TITLE])
    }

    @Test
    fun `a blank suggestion gives no generated description finding`() {
        val suggestion = """, "suggestedTitle": " ", "suggestedDescription": """""
        val findings = module.parseResponse(response(suggestion), "m", 7, changes)

        assertEquals(1, findings.size)
    }

    @Test
    fun `an attacker-controlled path cannot inject html or break the table row`() {
        val evil = listOf(FileChange("app/<img src=x onerror=alert(1)>\n| `x` | owned |.kt", ChangeType.ADDED, additions = 1))

        val walkthrough = checkNotNull(walkthrough(evil, emptyList()))

        assertFalse("<img" in walkthrough, walkthrough)
        assertEquals(3, walkthrough.trimEnd().lines().size, walkthrough)
        assertTrue("&lt;img src=x onerror=alert(1)&gt;" in walkthrough, walkthrough)
    }

    @Test
    fun `a backslash before a pipe cannot unescape it and split the row`() {
        val walkthrough = checkNotNull(walkthrough(listOf(FileChange("a\\|b.kt", ChangeType.ADDED, additions = 1)), emptyList()))

        assertTrue("a\\\\\\|b.kt" in walkthrough, walkthrough)
    }

    @Test
    fun `a normal path renders unchanged`() {
        val walkthrough = checkNotNull(walkthrough(changes, emptyList()))

        assertTrue("| `core` | `src/Parser.kt` | +1/-1 |" in walkthrough, walkthrough)
    }
}
