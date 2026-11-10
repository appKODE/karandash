package io.karandash.patterns

import io.karandash.models.finding.Mismatch
import io.karandash.models.support.KarandashJson
import io.karandash.models.support.WellKnownMetadataKeys
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PatternFixTest {
    private val conventional = "pomsky:('feat' | 'fix') ': ' .+"

    @Test
    fun `inserting the missing literal gives a deterministic value`() {
        val fix = PatternFix.of(conventional, "feat add x", "title")

        assertTrue(fix.deterministic)
        assertEquals("feat: add x", fix.value)
        assertEquals("Change the title to `feat: add x`.", fix.suggestion)
        assertEquals(Mismatch("feat add x", 4, "\": \""), fix.mismatch)
    }

    @Test
    fun `a literal picked among alternatives is a guess and not deterministic`() {
        val fix = PatternFix.of("^(feat|fix)!?: .+", "feat add", "title")

        assertFalse(fix.deterministic)
        assertEquals("feat!: add", fix.value)
        assertEquals("Change the title to match the format, e.g. `feat!: add`.", fix.suggestion)
    }

    @Test
    fun `several missing literals are inserted in turn`() {
        val fix = PatternFix.of("pomsky:'[' ['A'-'Z']+ ']' ' ' .+", "API fix", "title")

        assertTrue(fix.deterministic)
        assertEquals("[API] fix", fix.value)
    }

    @Test
    fun `a value the generator invents is synthetic`() {
        val fix = PatternFix.of("^[A-Z]+-\\d+: .+$", "add login", "title")

        assertFalse(fix.deterministic)
        assertNotNull(fix.value)
        assertNotEquals("add login", fix.value)
        assertTrue(fix.suggestion.startsWith("Change the title to match the format, e.g. `"), fix.suggestion)
        assertEquals(0, fix.mismatch?.position)
    }

    @Test
    fun `author test examples are preferred as the example`() {
        val pattern = "pomsky:test { match 'fix: crash on start'; } ('feat' | 'fix') ': ' .+"
        val fix = PatternFix.of(pattern, "chore stuff", "title")

        assertFalse(fix.deterministic)
        assertEquals("fix: crash on start", fix.value)
    }

    @Test
    fun `author test examples are listed in the pattern examples metadata`() {
        val pattern = "pomsky:test { match 'fix: crash'; match 'feat: add'; } ('feat' | 'fix') ': ' .+"
        val fix = PatternFix.of(pattern, "chore stuff", "title")

        assertEquals(listOf("fix: crash", "feat: add"), fix.examples)
        assertEquals("fix: crash\nfeat: add", fix.metadata()[WellKnownMetadataKeys.PATTERN_EXAMPLES])
        assertEquals(null, PatternFix.of("^[a-z]+$", "X", "title").metadata()[WellKnownMetadataKeys.PATTERN_EXAMPLES])
    }

    @Test
    fun `a named group is reported as the mismatching part`() {
        val fix = PatternFix.of("pomsky::ticket(['A'-'Z']+ '-' [digit]+) ': ' .+", "PROJ-x: fix", "title")

        assertEquals("ticket", fix.mismatch?.group)
        assertEquals(5, fix.mismatch?.position)
    }

    @Test
    fun `glob patterns fall back to a plain hint`() {
        val fix = PatternFix.of("glob:{feat,fix}: *", "chore: x", "title")

        assertEquals("Change the title to match the format.", fix.suggestion)
        assertNull(fix.mismatch)
        assertNull(fix.value)
        assertFalse(fix.deterministic)
        assertEquals("glob:{feat,fix}: *", fix.format)
    }

    @Test
    fun `known library patterns add a short hint`() {
        val fix = PatternFix.of("pomsky:jira_ticket ': ' .+", "add login", "title")

        assertTrue(fix.suggestion.contains("PROJ-123"), fix.suggestion)
    }

    @Test
    fun `format is the formatted pomsky source and regex patterns are decompiled`() {
        assertEquals("('feat' | 'fix') ': ' .+", PatternFix.of(conventional, "x", "title").format)
        val regexFormat = PatternFix.of("^(feat|fix): .+$", "x", "title").format
        assertNotNull(regexFormat)
        assertTrue(regexFormat.contains("feat"), regexFormat)
    }

    @Test
    fun `explanation follows the language and the diagram is drawn`() {
        val en = PatternFix.of(conventional, "x", "title")
        val ru = PatternFix.of(conventional, "x", "title", language = "ru-RU")

        assertNotNull(en.explanation)
        assertNotNull(ru.explanation)
        assertNotEquals(en.explanation, ru.explanation)
        assertNotNull(en.diagram)
    }

    @Test
    fun `metadata carries the fix hint keys`() {
        val meta = PatternFix.of(conventional, "feat add x", "title").metadata()

        assertEquals("('feat' | 'fix') ': ' .+", meta[WellKnownMetadataKeys.EXPECTED_FORMAT])
        val mismatch = KarandashJson.Default.decodeFromString(Mismatch.serializer(), checkNotNull(meta[WellKnownMetadataKeys.MISMATCH]))
        assertEquals(4, mismatch.position)
        assertNotNull(meta[WellKnownMetadataKeys.FORMAT_EXPLAIN])
        assertNotNull(meta[WellKnownMetadataKeys.FORMAT_DIAGRAM])
    }

    @Test
    fun `without diagnosis there is no mismatch but an example`() {
        val fix = PatternFix.of("^## Summary", "", "description", diagnose = false)

        assertNull(fix.mismatch)
        assertFalse(fix.deterministic)
        assertEquals("## Summary", fix.value)
    }

    private val conventionalRegex = "^(feat|fix|perf|refactor|style|test|docs|build|ci|chore|revert)(\\(.+\\))?!?:.*"
    private val longTitle = "Stabilization: fixes, unification and review quality (Phase 1)"

    @Test
    fun `expected alternatives of a regex pattern are whole words without duplicates`() {
        val expected = checkNotNull(PatternFix.of(conventionalRegex, longTitle, "title").mismatch).expected

        assertEquals("one of: feat, fix, perf, refactor, style, test, docs, build, ci, chore, revert", expected)
    }

    @Test
    fun `repeated alternatives are listed once`() {
        val expected = checkNotNull(PatternFix.of("pomsky:('aa' | 'aa' | 'bb') 'x'", "q", "title").mismatch).expected

        assertEquals("one of: aa, bb", expected)
    }

    @Test
    fun `where text names the alternatives and what was found`() {
        val where = checkNotNull(PatternFix.of(conventionalRegex, longTitle, "title").mismatch).describe()

        assertEquals(
            "`$longTitle` — at the start expected `one of: feat, fix, perf, refactor, style, test, docs, build, ci, chore, revert` — found `Stabilization`",
            where,
        )
    }

    @Test
    fun `example omits optional groups and uses the checked text once`() {
        val fix = PatternFix.of(conventionalRegex, longTitle, "title")

        assertEquals("feat: $longTitle", fix.value)
    }

    @Test
    fun `example keeps the text glued when the pattern forbids a space`() {
        val value = checkNotNull(PatternFix.of("^(feat|fix):[A-Z].*", "Add x", "title").value)

        assertEquals("feat:", value.take(5))
        assertTrue(Regex("^(feat|fix):[A-Z].*").matches(value), value)
    }

    @Test
    fun `named and regex prefixed patterns give a format and an example`() {
        val named = PatternFix.of("@kebab-case", "Bad Name", "branch")
        val prefixed = PatternFix.of("regex:^(feat|fix): .+$", "add x", "title")

        assertNotNull(named.format)
        assertEquals("feat: add x", prefixed.value)
    }

    @Test
    fun `reject cases are not offered as examples`() {
        val pattern = "pomsky:test { match 'fix: a'; reject 'nope'; } ('feat' | 'fix') ': ' .+"

        assertEquals(listOf("fix: a"), PatternFix.of(pattern, "x", "title").examples)
    }

    @Test
    fun `alternatives nested in a group or a repetition are listed`() {
        val grouped = PatternFix.of("^(?:(feat|fix)): .+", "chore: x", "title").mismatch
        val repeated = PatternFix.of("^(?:(feat|fix)-)+: .+", "chore: x", "title").mismatch

        assertEquals("one of: feat, fix", grouped?.expected)
        assertEquals("one of: feat, fix", repeated?.expected)
    }

    @Test
    fun `a pattern no example can satisfy gives the plain hint`() {
        val fix = PatternFix.of("^a(?=b)c$", "x", "title")

        assertNull(fix.value)
        assertEquals("Change the title to match the format.", fix.suggestion)
    }
}
