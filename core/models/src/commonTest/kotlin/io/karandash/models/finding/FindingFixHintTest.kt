package io.karandash.models.finding

import io.karandash.models.support.WellKnownMetadataKeys
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FindingFixHintTest {
    @BeforeTest
    fun registerPack() {
        registerTestPolishPack()
        registerTestRussianPack()
    }

    private val mismatch = Mismatch(value = "feat add x", position = 4, expected = "\": \"")

    private fun finding(
        metadata: Map<String, String> = emptyMap(),
        suggestion: String? = null,
    ) = Finding(severity = Severity.ERROR, message = "MR title does not match the required format", rule = "titleFormat")
        .copy(suggestion = suggestion, metadata = metadata)

    @Test
    fun `a mismatch written as metadata reads back`() {
        val f = finding(mapOf(WellKnownMetadataKeys.MISMATCH to mismatch.toMetadataValue()))

        assertEquals(mismatch, f.mismatch())
    }

    @Test
    fun `the mismatch metadata value is the documented JSON object`() {
        val withGroup = mismatch.copy(group = "type")

        assertEquals("""{"value":"feat add x","position":4,"expected":"\": \""}""", mismatch.toMetadataValue())
        assertEquals("""{"value":"feat add x","position":4,"expected":"\": \"","group":"type"}""", withGroup.toMetadataValue())
    }

    @Test
    fun `malformed mismatch metadata reads as absent while a valid one survives`() {
        val bad = finding(mapOf(WellKnownMetadataKeys.MISMATCH to "{not json"))
        val wrongShape = finding(mapOf(WellKnownMetadataKeys.MISMATCH to """{"value":"x"}"""))
        val good = finding(mapOf(WellKnownMetadataKeys.MISMATCH to mismatch.toMetadataValue()))

        assertNull(bad.mismatch())
        assertNull(wrongShape.mismatch())
        assertEquals(mismatch, good.mismatch())
    }

    @Test
    fun `a blank expected format reads as absent`() {
        assertNull(finding(mapOf(WellKnownMetadataKeys.EXPECTED_FORMAT to " ")).expectedFormat())
        assertEquals(
            "(\"feat\" | \"fix\") \": \" .+",
            finding(mapOf(WellKnownMetadataKeys.EXPECTED_FORMAT to "(\"feat\" | \"fix\") \": \" .+")).expectedFormat(),
        )
    }

    @Test
    fun `the where text names the matched prefix and the expected token`() {
        assertEquals("`feat add x` — after `feat` expected `\": \"` — found whitespace", mismatch.describe())
    }

    @Test
    fun `the where text at position zero says at the start`() {
        assertEquals("`add x` — at the start expected `\"feat\"` — found `add`", Mismatch("add x", 0, "\"feat\"").describe())
    }

    @Test
    fun `the where text names the group and follows the language`() {
        val grouped = mismatch.copy(group = "type")

        assertEquals("`feat add x` — after `feat` expected `\": \"` — found whitespace (part `type`)", grouped.describe())
        assertEquals("`feat add x` — после `feat` ожидается `\": \"` — найдено пробел (часть `type`)", grouped.describe("ru-RU"))
        assertEquals("`feat add x` — po `feat` oczekiwano `\": \"` — znaleziono spacja (część `type`)", grouped.describe("pl"))
    }

    @Test
    fun `the where text says nothing found at the end of the value`() {
        assertEquals("`feat` — after `feat` expected `\": \"` — found nothing", Mismatch("feat", 4, "\": \"").describe())
    }

    @Test
    fun `a value holding a backtick gets a longer code span`() {
        assertEquals("`` a`b `` — at the start expected `x` — found `a`", Mismatch("a`b", 0, "x").describe())
    }

    @Test
    fun `labels follow the language and fall back to English`() {
        assertEquals("How to fix", FixHintText.HOW_TO_FIX.text())
        assertEquals("Как исправить", FixHintText.HOW_TO_FIX.text("ru"))
        assertEquals("Gdzie", FixHintText.WHERE.text("pl_PL"))
        assertEquals("Format", FixHintText.FORMAT.text("xx"))
    }

    @Test
    fun `plain fix hint lines are labelled in Where How to fix Format order`() {
        val f = finding(
            mapOf(
                WellKnownMetadataKeys.MISMATCH to mismatch.toMetadataValue(),
                WellKnownMetadataKeys.EXPECTED_FORMAT to "(\"feat\" | \"fix\") \": \" .+",
            ),
            suggestion = "rename the title to `feat: add x`",
        )

        assertEquals(
            listOf(
                "Where: `feat add x` — after `feat` expected `\": \"` — found whitespace",
                "How to fix: rename the title to `feat: add x`",
                "Format: `(\"feat\" | \"fix\") \": \" .+`",
            ),
            f.fixHintLines(),
        )
    }

    @Test
    fun `a finding with only a suggestion has one fix hint line and none without it`() {
        assertEquals(listOf("How to fix: add the label"), finding(suggestion = "add the label").fixHintLines())
        assertEquals(emptyList(), finding().fixHintLines())
    }

    @Test
    fun `fix hint metadata keys are the four keys the fix hint renders`() {
        assertEquals(
            setOf(
                WellKnownMetadataKeys.EXPECTED_FORMAT,
                WellKnownMetadataKeys.MISMATCH,
                WellKnownMetadataKeys.FORMAT_EXPLAIN,
                WellKnownMetadataKeys.FORMAT_DIAGRAM,
            ),
            FIX_HINT_METADATA_KEYS,
        )
    }

    @Test
    fun `the matched prefix never ends inside a surrogate pair`() {
        val value = "a\uD83D\uDE00b"

        assertEquals("a", Mismatch(value = value, position = 2, expected = "x").matchedPrefix)
        assertEquals("a\uD83D\uDE00", Mismatch(value = value, position = 3, expected = "x").matchedPrefix)
    }
}
