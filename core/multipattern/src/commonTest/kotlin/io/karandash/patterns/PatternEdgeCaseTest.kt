package io.karandash.patterns

import io.karandash.api.platform.PatternDefinition
import io.karandash.cache.CacheManager
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import io.karandash.testfixtures.TestFiles
import kotlinx.io.files.Path
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PatternEdgeCaseTest {
    private val registry = CorePatternRegistry.create()
    private val resolver = PatternResolver(registry)

    @Test
    fun `case validation agrees with the case regex for every convention`() {
        val random = Random(5)
        val alphabet = "aZb9-_ x"
        val strict = CaseType.entries - setOf(CaseType.LOWER, CaseType.UPPER)
        repeat(500) {
            val text = List(random.nextInt(0, 8)) { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
            CaseType.entries.forEach { case ->
                val byRegex = text.isNotEmpty() && Regex(CaseValidator.toRegex(case)).matches(text)
                if (case in strict) {
                    assertEquals(byRegex, CaseValidator.matches(text, case), "$case '$text'")
                } else if (byRegex) {
                    assertTrue(CaseValidator.matches(text, case), "$case '$text'")
                }
            }
        }
        assertTrue(CaseValidator.matches("abc-1", CaseType.LOWER))
        assertTrue(CaseValidator.matches("ABC-1", CaseType.UPPER))
        assertEquals(
            CaseType.entries.size,
            CaseType.entries
                .map(CaseValidator::descriptionOf)
                .toSet()
                .size
        )
    }

    @Test
    fun `validator enums use their lowercase wire names`() {
        CaseType.entries.forEach { roundTrip(CaseType.serializer(), it) }
        TicketFormat.entries.forEach { roundTrip(TicketFormat.serializer(), it) }
        DelimiterType.entries.forEach { roundTrip(DelimiterType.serializer(), it) }
        assertEquals("\"kebab\"", Json.encodeToString(CaseType.serializer(), CaseType.KEBAB))
        assertEquals("\"youtrack\"", Json.encodeToString(TicketFormat.serializer(), TicketFormat.YOUTRACK))
        assertEquals("\"parens\"", Json.encodeToString(DelimiterType.serializer(), DelimiterType.PARENS))
    }

    private fun <T> roundTrip(
        serializer: KSerializer<T>,
        value: T,
    ) {
        val json = Json.encodeToString(serializer, value)
        assertEquals(json.lowercase(), json)
        assertEquals(value, Json.decodeFromString(serializer, json))
    }

    @Test
    fun `pattern aware compiler returns the regex or records an error finding`() {
        val compiler = PatternAwareCompilerImpl(resolver)
        val findings = mutableListOf<Finding>()

        assertTrue(checkNotNull(compiler.compile("@kebab-case", "r", findings)).matches("a-b"))
        assertNull(compiler.compile("regex:(", "r", findings))

        val finding = findings.single()
        assertEquals(Severity.ERROR, finding.severity)
        assertEquals("r", finding.rule)
        assertTrue(finding.message.startsWith("Invalid regex pattern"), finding.message)
    }

    @Test
    fun `unsafe regexes are rejected with a reason`() {
        val tooLong = resolver.resolve("regex:" + "a".repeat(501))
        val nested = resolver.resolve("regex:((a)+)+")

        assertIs<PatternCompilationResult.Error>(tooLong)
        assertTrue(tooLong.message.startsWith("Regex pattern too long (501 > 500"), tooLong.message)
        assertIs<PatternCompilationResult.Error>(nested)
        assertTrue(nested.message.contains("nested quantifiers"), nested.message)
    }

    @Test
    fun `named and glob failures keep the original pattern`() {
        val named = PatternResolver(
            DefaultPatternRegistry().apply {
                register(PatternDefinition(name = "broken", description = "d", expand = { "regex:(" }))
            },
        ).resolve("@broken")
        val globLong = resolver.resolve("glob:" + "a".repeat(600))
        val globInvalid = resolver.resolve("glob:[z-a]")

        assertIs<PatternCompilationResult.Error>(named)
        assertEquals("@broken", named.originalPattern)
        assertIs<PatternCompilationResult.Error>(globLong)
        assertTrue(globLong.message.startsWith("Glob pattern too long"), globLong.message)
        assertEquals("glob:" + "a".repeat(600), globLong.originalPattern)
        assertIs<PatternCompilationResult.Error>(globInvalid)
        assertTrue(globInvalid.message.startsWith("Glob expansion produced invalid regex"), globInvalid.message)
    }

    /** `createManaged` is live (pinned here), not dead code. */
    @Test
    fun `managed resolver caches results in the cache manager`() {
        val dir = TestFiles.createTestTempDirectory("mp-managed")
        val manager = CacheManager(cacheDir = dir)
        val managed = PatternResolver.createManaged(registry, manager)

        val first = managed.resolve("glob:*.kt")

        assertIs<PatternCompilationResult.Success>(first)
        assertSame(first, managed.resolve("glob:*.kt"))
        assertTrue(manager.stats().containsKey(PatternResolver.CACHE_NAME))
    }

    @Test
    fun `malformed references fail with a clear message`() {
        listOf(
            "@optional(x" to "missing closing parenthesis",
            "@optional(@:x, @k:v)" to "Malformed named argument",
        ).forEach { (pattern, message) ->
            val error = assertFailsWith<IllegalArgumentException> { NamedPatternExpander.expand(registry, pattern) }
            assertTrue(error.message.orEmpty().contains(message), error.message)
        }
        assertEquals(emptyMap(), NamedPatternExpander.extractParams(registry, "@optional()", emptySet(), 0))
    }

    @Test
    fun `positional arguments may nest calls and references`() {
        val nested = NamedPatternExpander.expand(registry, "@optional(@bracketed(@kebab-case))")
        val viaInner = NamedPatternExpander.resolveInnerReferences(registry, "x@optional(a)y", emptySet(), 0)
        val params = NamedPatternExpander.extractParams(registry, "@unknown-name(a, (b, c))", emptySet(), 0)

        assertEquals(
            NamedPatternExpander.expand(registry, "@optional(${NamedPatternExpander.expand(registry, "@bracketed(@kebab-case)")})"),
            nested
        )
        assertEquals("x${NamedPatternExpander.expand(registry, "@optional(a)")}y", viaInner)
        assertEquals(mapOf("arg0" to "a", "arg1" to "(b, c)"), params)
    }
}
