package io.karandash.validators

import io.karandash.config.Config
import io.karandash.config.RulesConfig
import io.karandash.helpers.RegexHelper
import io.karandash.models.Commit
import io.karandash.models.CommitAuthor
import io.karandash.patterns.CorePatternRegistry
import io.karandash.patterns.PatternAwareCompilerImpl
import io.karandash.patterns.PatternResolver
import io.karandash.testfixtures.createTestEngine
import io.karandash.testfixtures.createTestMergeRequest
import io.karandash.testfixtures.toJsonElement
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PatternResolutionIntegrationTest {
    @BeforeTest
    fun setUp() {
        val registry = CorePatternRegistry.create()
        JiraTicketRuleModule().registerPatterns(registry)
        TitleFormatRuleModule().registerPatterns(registry)
        val resolver = PatternResolver(registry = registry)
        RegexHelper.injectPatternCompiler(PatternAwareCompilerImpl(resolver))
    }

    @AfterTest
    fun tearDown() {
        RegexHelper.clearPatternCompiler()
    }

    // ------------------------------------------------------------------
    // Test 1: glob: prefix works in title-format
    // ------------------------------------------------------------------

    @Test
    fun `glob pattern in title-format matches valid title`() =
        runTest {
            val config = Config(
                rules = RulesConfig(
                    ruleConfigs = mapOf(
                        TitleFormatRuleModule.RULE_ID to TitleFormatConfig(
                            pattern = "glob:{feat,fix}: *",
                            minLength = 1,
                        ).toJsonElement(),
                    ),
                ),
            )
            val mr = createTestMergeRequest(title = "feat: add login")
            val engine = createTestEngine(config)
            val result = engine.validate(mr)

            val titleFindings = result.findings.filter { it.rule == TitleFormatRuleModule.RULE_ID }
            assertTrue(
                titleFindings.none { it.message.contains("pattern") || it.message.contains("does not match") },
                "feat: add login should match glob:{feat,fix}: * but got findings: $titleFindings",
            )
        }

    @Test
    fun `glob pattern in title-format rejects non-matching title`() =
        runTest {
            val config = Config(
                rules = RulesConfig(
                    ruleConfigs = mapOf(
                        TitleFormatRuleModule.RULE_ID to TitleFormatConfig(
                            pattern = "glob:{feat,fix}: *",
                            minLength = 1,
                        ).toJsonElement(),
                    ),
                ),
            )
            val mr = createTestMergeRequest(title = "chore: update deps")
            val engine = createTestEngine(config)
            val result = engine.validate(mr)

            val titleFindings = result.findings.filter { it.rule == TitleFormatRuleModule.RULE_ID }
            assertFalse(
                titleFindings.isEmpty(),
                "chore: update deps should not match glob:{feat,fix}: *",
            )
        }

    // ------------------------------------------------------------------
    // Test 2: regex: prefix works in title-format
    // ------------------------------------------------------------------

    @Test
    fun `regex prefix in title-format matches valid title`() =
        runTest {
            val config = Config(
                rules = RulesConfig(
                    ruleConfigs = mapOf(
                        TitleFormatRuleModule.RULE_ID to TitleFormatConfig(
                            pattern = """regex:^\[MAPS-\d+\] .+""",
                            minLength = 1,
                        ).toJsonElement(),
                    ),
                ),
            )
            val mr = createTestMergeRequest(title = "[MAPS-42] Fix bug")
            val engine = createTestEngine(config)
            val result = engine.validate(mr)

            val titleFindings = result.findings.filter { it.rule == TitleFormatRuleModule.RULE_ID }
            assertTrue(
                titleFindings.none { it.message.contains("does not match") },
                "[MAPS-42] Fix bug should match regex prefix pattern but got findings: $titleFindings",
            )
        }

    // ------------------------------------------------------------------
    // Test 3: @kebab-case named pattern resolves and matches
    // ------------------------------------------------------------------

    @Test
    fun `kebab-case named pattern matches kebab-case input`() =
        runTest {
            val config = Config(
                rules = RulesConfig(
                    ruleConfigs = mapOf(
                        TitleFormatRuleModule.RULE_ID to TitleFormatConfig(
                            pattern = "@kebab-case",
                        ).toJsonElement(),
                    ),
                ),
            )
            val mr = createTestMergeRequest(title = "hello-world")
            val engine = createTestEngine(config)
            val result = engine.validate(mr)

            val titleFindings = result.findings.filter { it.rule == TitleFormatRuleModule.RULE_ID }
            assertTrue(
                titleFindings.none { it.message.contains("does not match") },
                "hello-world should match @kebab-case but got findings: $titleFindings",
            )
        }

    @Test
    fun `kebab-case named pattern rejects non-kebab-case input`() =
        runTest {
            val config = Config(
                rules = RulesConfig(
                    ruleConfigs = mapOf(
                        TitleFormatRuleModule.RULE_ID to TitleFormatConfig(
                            pattern = "@kebab-case",
                        ).toJsonElement(),
                    ),
                ),
            )
            val mr = createTestMergeRequest(title = "HelloWorld")
            val engine = createTestEngine(config)
            val result = engine.validate(mr)

            val titleFindings = result.findings.filter { it.rule == TitleFormatRuleModule.RULE_ID }
            assertFalse(
                titleFindings.isEmpty(),
                "HelloWorld should not match @kebab-case",
            )
        }

    // ------------------------------------------------------------------
    // Test 4: bare regex still works (backward compat)
    // ------------------------------------------------------------------

    @Test
    fun `bare regex in title-format compiles and matches without pattern compiler`() =
        runTest {
            val config = Config(
                rules = RulesConfig(
                    ruleConfigs = mapOf(
                        TitleFormatRuleModule.RULE_ID to TitleFormatConfig(
                            pattern = "^[A-Z]+-\\d+",
                            minLength = 1,
                        ).toJsonElement(),
                    ),
                ),
            )
            val mr = createTestMergeRequest(title = "MAPS-123")
            val engine = createTestEngine(config)
            val result = engine.validate(mr)

            val titleFindings = result.findings.filter { it.rule == TitleFormatRuleModule.RULE_ID }
            assertTrue(
                titleFindings.none { it.message.contains("does not match") },
                "MAPS-123 should match bare regex ^[A-Z]+-\\d+ but got: $titleFindings",
            )
        }

    // ------------------------------------------------------------------
    // Test 5: @jira-ticket named pattern (requires JiraTicketRuleModule registered)
    // ------------------------------------------------------------------

    @Test
    fun `jira-ticket named pattern matches jira ticket format`() =
        runTest {
            val config = Config(
                rules = RulesConfig(
                    ruleConfigs = mapOf(
                        TitleFormatRuleModule.RULE_ID to TitleFormatConfig(
                            pattern = "@jira-ticket",
                            minLength = 1,
                        ).toJsonElement(),
                    ),
                ),
            )
            val mr = createTestMergeRequest(title = "PROJ-42")
            val engine = createTestEngine(config)
            val result = engine.validate(mr)

            val titleFindings = result.findings.filter { it.rule == TitleFormatRuleModule.RULE_ID }
            assertTrue(
                titleFindings.none { it.message.contains("does not match") },
                "PROJ-42 should match @jira-ticket but got findings: $titleFindings",
            )
        }

    // ------------------------------------------------------------------
    // Test 6: nonexistent named pattern produces error finding
    // ------------------------------------------------------------------

    @Test
    fun `nonexistent-pattern produces error finding`() =
        runTest {
            val config = Config(
                rules = RulesConfig(
                    ruleConfigs = mapOf(
                        TitleFormatRuleModule.RULE_ID to buildJsonObject {
                            put("pattern", "@nonexistent-pattern")
                        },
                    ),
                ),
            )
            val mr = createTestMergeRequest(title = "any title")
            val engine = createTestEngine(config)
            val result = engine.validate(mr)

            val titleFindings = result.findings.filter { it.rule == TitleFormatRuleModule.RULE_ID }
            assertFalse(
                titleFindings.isEmpty(),
                "An unresolvable @nonexistent-pattern should produce an error finding",
            )
        }

    @Test
    fun `commit body separated-list with a quoted separator matches comma separated tickets`() {
        val registry = CorePatternRegistry.create().also { JiraTicketRuleModule().registerPatterns(it) }
        val resolver = PatternResolver(registry = registry)
        // The pipeline's patternExpander expands inline references in config values before rules see them.
        val pattern = resolver.resolveNested("^T: @separated-list(@jira-ticket(@project:BRAVO), ', ')$")
        val validator = CommitBodyValidator(CommitBodyConfig(pattern = pattern, required = true), PatternAwareCompilerImpl(resolver))

        val passing = validator.validate(createTestMergeRequest(commits = listOf(commit("Feature\n\nT: BRAVO-1, BRAVO-2"))))
        val failing = validator.validate(createTestMergeRequest(commits = listOf(commit("Feature\n\nT: BRAVO-1 BRAVO-2"))))

        assertTrue(passing.isEmpty(), passing.joinToString { it.message })
        val suggestion = failing.single().suggestion.orEmpty()
        assertFalse("', '" in suggestion, suggestion)
    }

    private fun commit(message: String): Commit =
        Commit(sha = "abc1234", message = message, author = CommitAuthor("user", "user@example.com"), timestamp = "2024-01-01T00:00:00Z")
}
