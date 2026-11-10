package io.karandash.patterns

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PatternResolverTest {
    private val registry = FakePatternRegistry()
    private val resolver = PatternResolver(registry = registry, pomskyCompiler = null)

    // --- regex: prefix ---

    @Test
    fun `regex prefix compiles to Success`() {
        val result = resolver.resolve("regex:^[A-Z]+-\\d+$")
        assertIs<PatternCompilationResult.Success>(result)
        assertEquals(PatternType.REGEX, result.type)
        assertTrue(result.regex.matches("PROJ-123"))
    }

    @Test
    fun `regex prefix invalid pattern returns Error`() {
        val result = resolver.resolve("regex:[invalid")
        assertIs<PatternCompilationResult.Error>(result)
    }

    // --- bare (no prefix) ---

    @Test
    fun `bare pattern compiles as raw regex`() {
        val result = resolver.resolve("^feat:")
        assertIs<PatternCompilationResult.Success>(result)
        assertEquals(PatternType.BARE, result.type)
        assertTrue(result.regex.containsMatchIn("feat:add login"))
    }

    @Test
    fun `bare invalid pattern returns Error`() {
        val result = resolver.resolve("[unclosed")
        assertIs<PatternCompilationResult.Error>(result)
    }

    // --- glob: prefix ---

    @Test
    fun `glob prefix compiles to Success`() {
        val result = resolver.resolve("glob:{feat,fix}:*")
        assertIs<PatternCompilationResult.Success>(result)
        assertEquals(PatternType.GLOB, result.type)
        assertTrue(result.regex.matches("feat:add login"))
    }

    // --- named pattern ---

    @Test
    fun `named pattern resolved from registry`() {
        registry.register(
            PatternDefinition(
                name = "my-pattern",
                description = "test",
                params = emptyList(),
                expand = { "^hello$" },
            ),
        )
        val result = resolver.resolve("@my-pattern")
        assertIs<PatternCompilationResult.Success>(result)
        assertEquals(PatternType.NAMED, result.type)
        assertTrue(result.regex.matches("hello"))
    }

    @Test
    fun `unknown named pattern returns Error`() {
        val result = resolver.resolve("@does-not-exist")
        assertIs<PatternCompilationResult.Error>(result)
        assertTrue(result.message.contains("does-not-exist"))
    }

    @Test
    fun `named pattern pointing to glob is resolved`() {
        registry.register(
            PatternDefinition(
                name = "feat-or-fix",
                description = "test",
                params = emptyList(),
                expand = { "glob:{feat,fix}:*" },
            ),
        )
        val result = resolver.resolve("@feat-or-fix")
        assertIs<PatternCompilationResult.Success>(result)
        assertEquals(PatternType.NAMED, result.type)
        assertTrue(result.regex.matches("feat:something"))
    }

    // --- pomsky unavailable ---

    @Test
    fun `pomsky pattern returns Error when compiler unavailable`() {
        val result = resolver.resolve("pomsky:let t = 'feat'; ^ t")
        assertIs<PatternCompilationResult.Error>(result)
        assertTrue(result.message.contains("not available"))
    }

    // --- caching ---

    @Test
    fun `same pattern resolved twice returns equal result`() {
        val first = resolver.resolve("^test$")
        val second = resolver.resolve("^test$")
        assertIs<PatternCompilationResult.Success>(first)
        assertIs<PatternCompilationResult.Success>(second)
        assertEquals(first.type, second.type)
        assertEquals(first.originalPattern, second.originalPattern)
    }

    // --- original pattern preserved ---

    @Test
    fun `originalPattern reflects full input string including prefix`() {
        val pattern = "regex:^hello$"
        val result = resolver.resolve(pattern)
        assertIs<PatternCompilationResult.Success>(result)
        assertEquals(pattern, result.originalPattern)
    }

    // --- resolveNested ---

    @Test
    fun `resolveNested expands at-references in string`() {
        registry.register(
            PatternDefinition(
                name = "word",
                description = "test",
                params = emptyList(),
                expand = { "\\w+" },
            ),
        )
        val result = resolver.resolveNested("^@word$")
        assertEquals("^\\w+$", result)
    }

    @Test
    fun `resolveNested returns unchanged string without at-references`() {
        val result = resolver.resolveNested("^plain-regex$")
        assertEquals("^plain-regex$", result)
    }

    @Test
    fun `resolveNested expands multiple at-references`() {
        registry.register(
            PatternDefinition(name = "num", description = "test", params = emptyList(), expand = { "\\d+" }),
        )
        registry.register(
            PatternDefinition(name = "word", description = "test", params = emptyList(), expand = { "\\w+" }),
        )
        // Use ':' as separator since '-' is part of pattern identifiers
        val result = resolver.resolveNested("@word:@num")
        assertEquals("\\w+:\\d+", result)
    }
}

/** Minimal in-memory registry for tests. */
private class FakePatternRegistry : PatternRegistry {
    private val store = mutableMapOf<String, PatternDefinition>()

    override fun register(definition: PatternDefinition) {
        store[definition.name] = definition
    }

    override fun resolve(
        name: String,
        params: Map<String, String>
    ): String? = store[name]?.expand?.invoke(params)

    override fun exists(name: String): Boolean = store.containsKey(name)

    override fun all(): List<PatternDefinition> = store.values.toList()
}
