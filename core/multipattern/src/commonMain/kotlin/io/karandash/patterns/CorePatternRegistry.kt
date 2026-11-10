package io.karandash.patterns

/**
 * Factory that produces a [DefaultPatternRegistry] pre-loaded with the
 * universal built-in named patterns.
 *
 * Patterns are grouped into four categories:
 * - **Structural** -- wrapping / repetition combinators
 * - **Case** -- identifier casing conventions
 * - **Formats** -- common data formats (semver, email, URL, ...)
 * - **References** -- project references (JIRA, GitHub, git SHA, changelog)
 *
 * No domain-specific patterns (conventional-commit, etc.) are registered here;
 * those belong in their respective plugin modules.
 */
object CorePatternRegistry {
    /**
     * Creates and returns a new [DefaultPatternRegistry] populated with all
     * universal built-in patterns.
     */
    fun create(): DefaultPatternRegistry {
        val registry = DefaultPatternRegistry()
        registerStructural(registry)
        registerCase(registry)
        registerFormats(registry)
        registerReferences(registry)
        return registry
    }

    // ------------------------------------------------------------------
    // Structural patterns (5)
    // ------------------------------------------------------------------

    private fun registerStructural(registry: DefaultPatternRegistry) {
        registry.register(
            PatternDefinition(
                name = "bracketed",
                description = "Wraps the inner pattern in square brackets: [P]",
                params = listOf(PatternParam(name = PARAM_PATTERN, description = DESC_INNER)),
                examples = listOf("[foo]", "[123]"),
                counterExamples = listOf("foo", "(foo)"),
                expand = { params ->
                    val inner = requireNotNull(params[PARAM_PATTERN]) { "@bracketed requires a '$PARAM_PATTERN' parameter" }
                    "\\[$inner\\]"
                },
            ),
        )
        registry.register(
            PatternDefinition(
                name = "parens",
                description = "Wraps the inner pattern in parentheses: (P)",
                params = listOf(PatternParam(name = PARAM_PATTERN, description = DESC_INNER)),
                examples = listOf("(foo)", "(123)"),
                counterExamples = listOf("foo", "[foo]"),
                expand = { params ->
                    val inner = requireNotNull(params[PARAM_PATTERN]) { "@parens requires a '$PARAM_PATTERN' parameter" }
                    "\\($inner\\)"
                },
            ),
        )
        registry.register(
            PatternDefinition(
                name = "optional",
                description = "Makes the inner pattern optional: (?:P)?",
                params = listOf(PatternParam(name = PARAM_PATTERN, description = DESC_INNER)),
                examples = listOf("foo", ""),
                counterExamples = listOf("bar"),
                expand = { params ->
                    val inner = requireNotNull(params[PARAM_PATTERN]) { "@optional requires a '$PARAM_PATTERN' parameter" }
                    "(?:$inner)?"
                },
            ),
        )
        registry.register(
            PatternDefinition(
                name = "one-or-more",
                description = "Requires one or more occurrences of the inner pattern: (?:P)+",
                params = listOf(PatternParam(name = PARAM_PATTERN, description = DESC_INNER)),
                examples = listOf("ab", "ababab"),
                counterExamples = listOf(""),
                expand = { params ->
                    val inner = requireNotNull(params[PARAM_PATTERN]) { "@one-or-more requires a '$PARAM_PATTERN' parameter" }
                    "(?:$inner)+"
                },
            ),
        )
        registry.register(
            PatternDefinition(
                name = "separated-list",
                description = "Matches a separator-delimited list: P(?:SP)*",
                params = listOf(
                    PatternParam(name = PARAM_PATTERN, description = "Element pattern"),
                    PatternParam(name = PARAM_SEPARATOR, description = "Separator pattern"),
                ),
                examples = listOf("a", "a,b,c"),
                counterExamples = listOf("a,"),
                expand = { params ->
                    val p = requireNotNull(params[PARAM_PATTERN]) { "@separated-list requires a '$PARAM_PATTERN' parameter" }
                    val s = requireNotNull(params[PARAM_SEPARATOR]) { "@separated-list requires a '$PARAM_SEPARATOR' parameter" }
                    "$p(?:$s$p)*"
                },
            ),
        )
    }

    // ------------------------------------------------------------------
    // Case patterns (9)
    // ------------------------------------------------------------------

    private fun registerCase(registry: DefaultPatternRegistry) {
        registry.register(
            simpleWithExamples(
                "kebab-case",
                "kebab-case identifier",
                KEBAB_CASE,
                examples = listOf("my-feature", "fix-bug-123"),
                counterExamples = listOf("myFeature", "my_feature", "MyFeature"),
            ),
        )
        registry.register(
            simpleWithExamples(
                "snake-case",
                "snake_case identifier",
                SNAKE_CASE,
                examples = listOf("my_function", "get_user_by_id"),
                counterExamples = listOf("myFunction", "my-function"),
            ),
        )
        registry.register(
            simpleWithExamples(
                "camel-case",
                "camelCase identifier",
                CAMEL_CASE,
                examples = listOf("myVariable", "getUserById"),
                counterExamples = listOf("MyVariable", "my_variable", "my-variable"),
            ),
        )
        registry.register(
            simpleWithExamples(
                "pascal-case",
                "PascalCase identifier",
                PASCAL_CASE,
                examples = listOf("MyClass", "GetUserById"),
                counterExamples = listOf("myClass", "my_class"),
            ),
        )
        registry.register(
            simpleWithExamples(
                "screaming-snake",
                "SCREAMING_SNAKE_CASE identifier",
                SCREAMING_SNAKE,
                examples = listOf("MAX_SIZE", "HTTP_STATUS"),
                counterExamples = listOf("maxSize", "max_size"),
            ),
        )
        registry.register(
            simpleWithExamples(
                "sentence-case",
                "Sentence case -- first letter uppercase",
                SENTENCE_CASE,
                examples = listOf("Hello world", "Fix the bug"),
                counterExamples = listOf("hello world", "fix the bug"),
            ),
        )
        registry.register(
            simpleWithExamples(
                "lower",
                "All lowercase letters",
                LOWER,
                examples = listOf("abc", "hello"),
                counterExamples = listOf("Abc", "abc1"),
            ),
        )
        registry.register(
            simpleWithExamples(
                "upper",
                "All uppercase letters",
                UPPER,
                examples = listOf("ABC", "HELLO"),
                counterExamples = listOf("Abc", "abc"),
            ),
        )
        registry.register(
            simpleWithExamples(
                "slug",
                "URL-safe slug (lowercase alphanumeric with hyphens)",
                SLUG,
                examples = listOf("hello-world", "my-page-123"),
                counterExamples = listOf("Hello", "hello_world"),
            ),
        )
    }

    // ------------------------------------------------------------------
    // Common format patterns (8)
    // ------------------------------------------------------------------

    private fun registerFormats(registry: DefaultPatternRegistry) {
        registry.register(
            simpleWithExamples(
                "semver",
                "Semantic version (e.g. 1.2.3-alpha.1+build.42)",
                SEMVER,
                examples = listOf("1.2.3", "0.1.0-alpha", "2.0.0-rc.1+build.42"),
                counterExamples = listOf("1.2", "v1.2.3"),
            ),
        )
        registry.register(
            simpleWithExamples(
                "email",
                "Email address",
                EMAIL,
                examples = listOf("user@example.com", "first.last@domain.co"),
                counterExamples = listOf("@example.com", "user@", "not-an-email"),
            ),
        )
        registry.register(
            simpleWithExamples(
                "url",
                "HTTP/HTTPS URL",
                URL,
                examples = listOf("https://example.com", "http://localhost:8080/path"),
                counterExamples = listOf("ftp://files.com", "not-a-url"),
            ),
        )
        registry.register(
            simpleWithExamples(
                "uuid",
                "UUID v4 (lowercase hex)",
                UUID,
                examples = listOf("550e8400-e29b-41d4-a716-446655440000"),
                counterExamples = listOf("not-a-uuid", "550e8400e29b41d4a716446655440000"),
            ),
        )
        registry.register(
            simpleWithExamples(
                "date-iso",
                "ISO 8601 date (YYYY-MM-DD)",
                DATE_ISO,
                examples = listOf("2024-03-30", "2000-01-01"),
                counterExamples = listOf("03-30-2024", "2024/03/30"),
            ),
        )
        registry.register(
            simpleWithExamples(
                "datetime-iso",
                "ISO 8601 datetime with timezone",
                DATETIME_ISO,
                examples = listOf("2024-03-30T12:00:00Z", "2024-03-30T12:00:00+01:00"),
                counterExamples = listOf("2024-03-30", "2024-03-30 12:00:00"),
            ),
        )
        registry.register(
            simpleWithExamples(
                "ipv4",
                "IPv4 address (dotted decimal)",
                IPV4,
                examples = listOf("192.168.1.1", "0.0.0.0"),
                counterExamples = listOf("192.168.1", "not.an.ip"),
            ),
        )
        registry.register(
            simpleWithExamples(
                "git-sha",
                "Git commit SHA (7-40 hex characters)",
                GIT_SHA,
                examples = listOf("a1b2c3d", "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2"),
                counterExamples = listOf("a1b2c3", "not-hex-chars-here"),
            ),
        )
    }

    // ------------------------------------------------------------------
    // Reference patterns (3)
    // ------------------------------------------------------------------

    private fun registerReferences(registry: DefaultPatternRegistry) {
        registry.register(
            PatternDefinition(
                name = "jira-ticket",
                description = "JIRA ticket reference (PROJECT-123)",
                params = listOf(
                    PatternParam(
                        name = PARAM_KEY,
                        description = "Project key prefix regex",
                        defaultValue = DEFAULT_JIRA_KEY,
                    ),
                ),
                examples = listOf("PROJ-123", "ABC-1"),
                counterExamples = listOf("proj-123", "123", "PROJ", "A-1", "SHA-256", "UTF-8"),
                expand = { params ->
                    val key = params[PARAM_KEY] ?: DEFAULT_JIRA_KEY
                    "($key)-\\d+"
                },
            ),
        )
        registry.register(
            simpleWithExamples(
                "github-issue",
                "GitHub issue or PR reference",
                GITHUB_ISSUE,
                examples = listOf("#123", "org/repo#456"),
                counterExamples = listOf("123", "#"),
            ),
        )
        registry.register(
            simpleWithExamples(
                "changelog-entry",
                "Keep a Changelog section header",
                CHANGELOG_ENTRY,
                examples = listOf("## [1.2.3] - 2024-03-30", "## [Unreleased]"),
                counterExamples = listOf("# Changes", "Version 1.2.3"),
            ),
        )
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Creates a parameter-less pattern definition that always returns [regex]. */
    private fun simpleWithExamples(
        name: String,
        description: String,
        regex: String,
        examples: List<String> = emptyList(),
        counterExamples: List<String> = emptyList(),
    ): PatternDefinition =
        PatternDefinition(
            name = name,
            description = description,
            params = emptyList(),
            examples = examples,
            counterExamples = counterExamples,
            expand = { regex },
        )

    // ------------------------------------------------------------------
    // Parameter name constants (avoid StringLiteralDuplication)
    // ------------------------------------------------------------------

    private const val PARAM_PATTERN = "pattern"
    private const val PARAM_SEPARATOR = "separator"
    private const val PARAM_KEY = "key"
    private const val DESC_INNER = "Inner pattern"
    private const val DEFAULT_JIRA_KEY = "[A-Z]{2,10}"

    // ------------------------------------------------------------------
    // Regex constants - case
    // ------------------------------------------------------------------

    private const val KEBAB_CASE = "[a-z][a-z0-9]*(-[a-z0-9]+)*"
    private const val SNAKE_CASE = "[a-z][a-z0-9]*(_[a-z0-9]+)*"
    private const val CAMEL_CASE = "[a-z][a-zA-Z0-9]*"
    private const val PASCAL_CASE = "[A-Z][a-zA-Z0-9]*"
    private const val SCREAMING_SNAKE = "[A-Z][A-Z0-9]*(_[A-Z0-9]+)*"
    private const val SENTENCE_CASE = "[A-Z][a-z].*"
    private const val LOWER = "[a-z]+"
    private const val UPPER = "[A-Z]+"
    private const val SLUG = "[a-z0-9]+(-[a-z0-9]+)*"

    // ------------------------------------------------------------------
    // Regex constants - formats
    // ------------------------------------------------------------------

    private const val SEMVER =
        """\d+\.\d+\.\d+(?:-[a-zA-Z0-9]+(?:\.[a-zA-Z0-9]+)*)?(?:\+[a-zA-Z0-9]+(?:\.[a-zA-Z0-9]+)*)?"""
    private const val EMAIL = "[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}"
    private const val URL = "https?://[^\\s]+"
    private const val UUID = "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"
    private const val DATE_ISO = "\\d{4}-\\d{2}-\\d{2}"
    private const val DATETIME_ISO =
        "\\d{4}-\\d{2}-\\d{2}T(?:[01]\\d|2[0-3]):[0-5]\\d:[0-5]\\d(?:Z|[+-](?:[01]\\d|2[0-3]):[0-5]\\d)"
    private const val IPV4 = "\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}"
    private const val GIT_SHA = "[0-9a-fA-F]{7,40}"

    // ------------------------------------------------------------------
    // Regex constants - references
    // ------------------------------------------------------------------

    private const val GITHUB_ISSUE = "(?:[a-zA-Z0-9_.-]+/[a-zA-Z0-9_.-]+)?#\\d+"
    private const val CHANGELOG_ENTRY =
        "## \\[(?:\\d+\\.\\d+\\.\\d+|Unreleased)\\](?: - \\d{4}-\\d{2}-\\d{2})?"
}
