package io.karandash.patterns

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CorePatternRegistryTest {
    private val registry = CorePatternRegistry.create()

    // ------------------------------------------------------------------
    // Registry completeness
    // ------------------------------------------------------------------

    @Test
    fun `all 25 patterns are registered`() {
        val expected = setOf(
            // structural
            "bracketed",
            "parens",
            "optional",
            "one-or-more",
            "separated-list",
            // case
            "kebab-case",
            "snake-case",
            "camel-case",
            "pascal-case",
            "screaming-snake",
            "sentence-case",
            "lower",
            "upper",
            "slug",
            // formats
            "semver",
            "email",
            "url",
            "uuid",
            "date-iso",
            "datetime-iso",
            "ipv4",
            "git-sha",
            // references
            "jira-ticket",
            "github-issue",
            "changelog-entry",
        )
        assertEquals(expected, registry.all().map { it.name }.toSet())
    }

    // ------------------------------------------------------------------
    // Structural patterns
    // ------------------------------------------------------------------

    @Test
    fun `bracketed wraps pattern in square brackets`() {
        val regex = compilePattern("bracketed", mapOf("pattern" to "foo"))
        assertTrue(regex.matches("[foo]"))
        assertFalse(regex.matches("foo"))
    }

    @Test
    fun `parens wraps pattern in parentheses`() {
        val regex = compilePattern("parens", mapOf("pattern" to "foo"))
        assertTrue(regex.matches("(foo)"))
        assertFalse(regex.matches("foo"))
    }

    @Test
    fun `optional makes inner pattern optional`() {
        val regex = compilePattern("optional", mapOf("pattern" to "foo"))
        assertTrue(regex.matches("foo"))
        assertTrue(regex.matches(""))
        assertFalse(regex.matches("bar"))
    }

    @Test
    fun `one-or-more requires at least one occurrence`() {
        val regex = compilePattern("one-or-more", mapOf("pattern" to "ab"))
        assertTrue(regex.matches("ab"))
        assertTrue(regex.matches("ababab"))
        assertFalse(regex.matches(""))
    }

    @Test
    fun `separated-list matches comma-separated items`() {
        val regex = compilePattern("separated-list", mapOf("pattern" to "\\w+", "separator" to ","))
        assertTrue(regex.matches("a"))
        assertTrue(regex.matches("a,b,c"))
        assertFalse(regex.matches("a,"))
    }

    // ------------------------------------------------------------------
    // Case patterns
    // ------------------------------------------------------------------

    @Test
    fun `kebab-case matches valid kebab identifiers`() {
        val r = compileSimple("kebab-case")
        assertTrue(r.matches("foo"))
        assertTrue(r.matches("foo-bar"))
        assertTrue(r.matches("foo-bar-baz"))
        assertFalse(r.matches("Foo-bar"))
        assertFalse(r.matches("foo--bar"))
        assertFalse(r.matches("-foo"))
    }

    @Test
    fun `snake-case matches valid snake identifiers`() {
        val r = compileSimple("snake-case")
        assertTrue(r.matches("foo"))
        assertTrue(r.matches("foo_bar"))
        assertFalse(r.matches("Foo_bar"))
        assertFalse(r.matches("foo__bar"))
    }

    @Test
    fun `camel-case matches camelCase identifiers`() {
        val r = compileSimple("camel-case")
        assertTrue(r.matches("fooBar"))
        assertTrue(r.matches("foo"))
        assertFalse(r.matches("FooBar"))
        assertFalse(r.matches("foo bar"))
    }

    @Test
    fun `pascal-case matches PascalCase identifiers`() {
        val r = compileSimple("pascal-case")
        assertTrue(r.matches("FooBar"))
        assertTrue(r.matches("Foo"))
        assertFalse(r.matches("fooBar"))
    }

    @Test
    fun `screaming-snake matches SCREAMING_SNAKE identifiers`() {
        val r = compileSimple("screaming-snake")
        assertTrue(r.matches("FOO"))
        assertTrue(r.matches("FOO_BAR"))
        assertFalse(r.matches("foo_bar"))
        assertFalse(r.matches("Foo_Bar"))
    }

    @Test
    fun `sentence-case matches sentence-case strings`() {
        val r = compileSimple("sentence-case")
        assertTrue(r.containsMatchIn("Hello world"))
        assertTrue(r.matches("Hello"))
        assertFalse(r.containsMatchIn("hello world"))
    }

    @Test
    fun `lower matches lowercase strings`() {
        val r = compileSimple("lower")
        assertTrue(r.matches("abc"))
        assertFalse(r.matches("Abc"))
        assertFalse(r.matches("abc1"))
    }

    @Test
    fun `upper matches uppercase strings`() {
        val r = compileSimple("upper")
        assertTrue(r.matches("ABC"))
        assertFalse(r.matches("Abc"))
    }

    @Test
    fun `slug matches url-safe slugs`() {
        val r = compileSimple("slug")
        assertTrue(r.matches("hello"))
        assertTrue(r.matches("hello-world"))
        assertTrue(r.matches("hello123"))
        assertFalse(r.matches("Hello"))
        assertFalse(r.matches("hello_world"))
    }

    // ------------------------------------------------------------------
    // Format patterns
    // ------------------------------------------------------------------

    @Test
    fun `semver matches valid versions`() {
        val r = compileSimple("semver")
        assertTrue(r.containsMatchIn("1.2.3"))
        assertTrue(r.containsMatchIn("1.0.0-alpha.1"))
        assertTrue(r.containsMatchIn("1.0.0+build.42"))
        assertTrue(r.containsMatchIn("1.0.0-alpha.1+build.42"))
        assertFalse(r.containsMatchIn("1.2"))
    }

    @Test
    fun `email matches valid email addresses`() {
        val r = compileSimple("email")
        assertTrue(r.containsMatchIn("user@example.com"))
        assertTrue(r.containsMatchIn("user.name+tag@sub.example.co.uk"))
        assertFalse(r.containsMatchIn("not-an-email"))
    }

    @Test
    fun `url matches http and https URLs`() {
        val r = compileSimple("url")
        assertTrue(r.containsMatchIn("https://example.com"))
        assertTrue(r.containsMatchIn("http://example.com/path?q=1"))
        assertFalse(r.containsMatchIn("ftp://example.com"))
    }

    @Test
    fun `uuid matches valid UUID v4 strings`() {
        val r = compileSimple("uuid")
        assertTrue(r.containsMatchIn("550e8400-e29b-41d4-a716-446655440000"))
        assertFalse(r.containsMatchIn("not-a-uuid"))
    }

    @Test
    fun `date-iso matches ISO 8601 dates`() {
        val r = compileSimple("date-iso")
        assertTrue(r.containsMatchIn("2024-01-15"))
        assertFalse(r.containsMatchIn("24-1-15"))
        assertFalse(r.containsMatchIn("2024/01/15"))
    }

    @Test
    fun `ipv4 matches dotted decimal addresses`() {
        val r = compileSimple("ipv4")
        assertTrue(r.containsMatchIn("192.168.1.1"))
        assertTrue(r.containsMatchIn("0.0.0.0"))
        assertFalse(r.containsMatchIn("192.168.1"))
    }

    // ------------------------------------------------------------------
    // New format patterns
    // ------------------------------------------------------------------

    @Test
    fun `datetime-iso matches ISO 8601 datetime with timezone`() {
        val r = compileSimple("datetime-iso")
        assertTrue(r.containsMatchIn("2024-03-30T12:00:00Z"))
        assertTrue(r.containsMatchIn("2024-03-30T12:00:00+01:00"))
        assertFalse(r.containsMatchIn("2024-03-30 12:00:00"))
    }

    @Test
    fun `git-sha matches 7 to 40 hex character strings`() {
        val r = compileSimple("git-sha")
        assertTrue(r.containsMatchIn("a1b2c3d"))
        assertTrue(r.containsMatchIn("a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2"))
        assertFalse(r.containsMatchIn("a1b2c3"))
        assertFalse(r.containsMatchIn("zzzzzzzz"))
    }

    // ------------------------------------------------------------------
    // Reference patterns
    // ------------------------------------------------------------------

    @Test
    fun `jira-ticket matches default PROJECT-123 format`() {
        val r = compilePattern("jira-ticket", emptyMap())
        assertTrue(r.containsMatchIn("PROJ-123"))
        assertTrue(r.containsMatchIn("ABC-1"))
        assertFalse(r.containsMatchIn("proj-123"))
        assertFalse(r.containsMatchIn("PROJ"))
    }

    @Test
    fun `jira-ticket matches custom key prefix`() {
        val r = compilePattern("jira-ticket", mapOf("key" to "MYPROJ"))
        assertTrue(r.containsMatchIn("MYPROJ-42"))
        assertFalse(r.containsMatchIn("OTHER-42"))
    }

    @Test
    fun `github-issue matches issue references`() {
        val r = compileSimple("github-issue")
        assertTrue(r.containsMatchIn("#123"))
        assertTrue(r.containsMatchIn("org/repo#456"))
        assertFalse(r.containsMatchIn("123"))
    }

    @Test
    fun `changelog-entry matches keep-a-changelog headers`() {
        val r = compileSimple("changelog-entry")
        assertTrue(r.containsMatchIn("## [1.2.3] - 2024-03-30"))
        assertTrue(r.containsMatchIn("## [Unreleased]"))
        assertFalse(r.containsMatchIn("# Changes"))
        assertFalse(r.containsMatchIn("Version 1.2.3"))
    }

    // ------------------------------------------------------------------
    // Examples and counterExamples metadata
    // ------------------------------------------------------------------

    @Test
    fun `all patterns have examples defined`() {
        for (pattern in registry.all()) {
            assertTrue(
                pattern.examples.isNotEmpty() || pattern.params.isNotEmpty(),
                "Pattern @${pattern.name} should have examples or params",
            )
        }
    }

    @Test
    fun `examples match and counterExamples reject for simple patterns`() {
        for (pattern in registry.all()) {
            if (pattern.params.isNotEmpty()) continue
            val raw = assertNotNull(registry.resolve(pattern.name), "Pattern @${pattern.name} not found")
            val regex = Regex(raw)
            for (example in pattern.examples) {
                assertTrue(
                    regex.containsMatchIn(example),
                    "@${pattern.name} example '$example' should match regex: $raw",
                )
            }
            for (counter in pattern.counterExamples) {
                assertFalse(
                    regex.matches(counter),
                    "@${pattern.name} counterExample '$counter' should not fully match regex: $raw",
                )
            }
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun compileSimple(name: String): Regex {
        val raw = assertNotNull(registry.resolve(name), "Pattern @$name not found")
        return Regex(raw)
    }

    private fun compilePattern(
        name: String,
        params: Map<String, String>
    ): Regex {
        val raw = assertNotNull(registry.resolve(name, params), "Pattern @$name not found")
        return Regex(raw)
    }
}
