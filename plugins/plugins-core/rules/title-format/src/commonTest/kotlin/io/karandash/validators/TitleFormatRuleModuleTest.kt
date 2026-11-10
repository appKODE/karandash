package io.karandash.validators

import io.karandash.models.finding.Severity
import io.karandash.models.support.WellKnownMetadataKeys
import io.karandash.patterns.DefaultPatternRegistry
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TitleFormatRuleModuleTest {
    private val module = TitleFormatRuleModule()

    private fun obj(vararg pairs: Pair<String, JsonElement>) = JsonObject(mapOf(*pairs))

    private suspend fun validate(
        title: String,
        config: JsonElement,
        sourceBranch: String = "feature/x",
    ) = module.validate(
        TitleFormatRuleModule.RULE_ID,
        createTestMergeRequest(title = title, sourceBranch = sourceBranch),
        config,
    )

    @Test
    fun `module metadata`() =
        runTest {
            assertEquals("io.karandash.rule.title-format", module.id)
            assertEquals(setOf("titleFormat"), module.contributedRules())
            assertNotNull(module.ruleExplanation("titleFormat"))
        }

    @Test
    fun `empty config accepts any title`() =
        runTest {
            assertTrue(validate("whatever.", JsonObject(emptyMap())).isEmpty())
        }

    @Test
    fun `pattern mismatch carries pattern metadata`() =
        runTest {
            val finding = validate("bad title", obj("pattern" to JsonPrimitive("^\\[[A-Z]+\\] .+$"))).single()

            assertEquals(Severity.ERROR, finding.severity)
            assertEquals("MR title does not match required pattern.", finding.message)
            assertTrue(finding.metadata.orEmpty().containsKey(WellKnownMetadataKeys.PATTERN))
            assertTrue(finding.metadata.orEmpty().containsKey(WellKnownMetadataKeys.EXPECTED_FORMAT))
        }

    @Test
    fun `structured type uses default conventional types`() =
        runTest {
            val config = obj("type" to JsonObject(emptyMap()))

            assertTrue(validate("feat(ui)!: add button", config).isEmpty())
            assertTrue(validate("perf: faster", config).isEmpty())
            assertEquals(1, validate("feature: nope", config).size)
        }

    @Test
    fun `structured type allowed list and required scope`() =
        runTest {
            val config =
                obj(
                    "type" to obj("allowed" to JsonArray(listOf(JsonPrimitive("build")))),
                    "scope" to obj("required" to JsonPrimitive(true)),
                )

            assertTrue(validate("build(ci): bump", config).isEmpty())
            assertEquals(1, validate("build: bump", config).size, "scope is required")
            assertEquals(1, validate("feat(ci): bump", config).size, "type not allowed")
        }

    @Test
    fun `explicit pattern wins over structured fields`() =
        runTest {
            val config = obj("pattern" to JsonPrimitive("^x$"), "type" to JsonObject(emptyMap()))

            assertTrue(validate("x", config).isEmpty())
        }

    @Test
    fun `subject min length and no period`() =
        runTest {
            val config =
                obj(
                    "type" to JsonObject(emptyMap()),
                    "subject" to obj("minLength" to JsonPrimitive(5), "noPeriod" to JsonPrimitive(true)),
                )

            assertTrue(validate("fix: abcde", config).isEmpty())
            assertEquals(1, validate("fix: abcd", config).size, "subject too short")
            val messages = validate("fix: abcdef.", config).map { it.message }
            assertTrue("MR title must not end with a period." in messages, messages.toString())
        }

    @Test
    fun `subject min length alone and no period alone`() =
        runTest {
            val minOnly = obj("type" to JsonObject(emptyMap()), "subject" to obj("minLength" to JsonPrimitive(3)))
            assertTrue(validate("fix: abc.", minOnly).isEmpty())
            assertEquals(1, validate("fix: ab", minOnly).size)

            val noPeriodOnly = obj("type" to JsonObject(emptyMap()), "subject" to obj("noPeriod" to JsonPrimitive(true)))
            assertTrue(validate("fix: a b", noPeriodOnly).isEmpty())
            assertEquals(2, validate("fix: a b.", noPeriodOnly).size, "pattern and period findings")
        }

    @Test
    fun `subject min length zero with no period is a valid config`() =
        runTest {
            val config =
                obj(
                    "type" to JsonObject(emptyMap()),
                    "subject" to obj("minLength" to JsonPrimitive(0), "noPeriod" to JsonPrimitive(true)),
                )

            assertTrue(validate("fix: ok", config).isEmpty())
        }

    @Test
    fun `require ticket suggests a title with the ticket`() =
        runTest {
            val config = obj("requireTicket" to JsonPrimitive(true))

            assertTrue(validate("PROJ-12 add login", config).isEmpty())
            val finding = validate("add login", config, sourceBranch = "feature/PROJ-42-login").single()
            assertEquals("MR title has no ticket reference.", finding.message)
            assertTrue(finding.suggestion.orEmpty().contains("PROJ-42"), finding.suggestion)
        }

    @Test
    fun `length bounds give error for short and warning for long`() =
        runTest {
            val config = obj("minLength" to JsonPrimitive(3), "maxLength" to JsonPrimitive(5))

            assertEquals(
                listOf(Severity.ERROR to "MR title too short (min: 3, actual: 2)"),
                validate("ab", config).map { it.severity to it.message },
            )
            assertEquals(
                listOf(Severity.WARNING to "MR title too long (max: 5, actual: 6)"),
                validate("abcdef", config).map { it.severity to it.message },
            )
        }

    @Test
    fun `invalid pattern returns only the regex error on every call`() =
        runTest {
            val config = obj("pattern" to JsonPrimitive("(unclosed"), "minLength" to JsonPrimitive(100))

            assertEquals(1, validate("short", config).size)
            assertEquals(1, validate("short", config).size)
        }

    @Test
    fun `conventional-commit named pattern respects types param`() {
        val registry = DefaultPatternRegistry()
        module.registerPatterns(registry)

        val defaults = Regex(assertNotNull(registry.resolve("conventional-commit")))
        assertTrue(defaults.matches("docs(readme): typo"))
        assertFalse(defaults.matches("build: x"))

        val custom = Regex(assertNotNull(registry.resolve("conventional-commit", mapOf("types" to "build, ci"))))
        assertTrue(custom.matches("ci!: drop job"))
        assertFalse(custom.matches("feat: x"))
    }
}
