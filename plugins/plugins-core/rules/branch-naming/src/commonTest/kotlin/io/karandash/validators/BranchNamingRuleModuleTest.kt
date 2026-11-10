package io.karandash.validators

import io.karandash.models.finding.Severity
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

class BranchNamingRuleModuleTest {
    private val module = BranchNamingRuleModule()

    private fun strings(vararg values: String) = JsonArray(values.map { JsonPrimitive(it) })

    private fun obj(vararg pairs: Pair<String, JsonElement>) = JsonObject(mapOf(*pairs))

    private suspend fun validate(
        config: JsonElement,
        source: String = "feature/login",
        target: String = "main",
    ) = module.validate(
        BranchNamingRuleModule.RULE_ID,
        createTestMergeRequest(sourceBranch = source, targetBranch = target),
        config,
    )

    @Test
    fun `module metadata`() =
        runTest {
            assertEquals("io.karandash.rule.branch-naming", module.id)
            assertEquals(setOf("branchNaming"), module.contributedRules())
            assertNotNull(module.ruleExplanation("branchNaming"))
            assertTrue(module.hoverDocumentation.orEmpty().contains("allowedPatterns"))
        }

    /** Hover keeps the defaults the removed hand-written text carried. */
    @Test
    fun `hover lists keys with their defaults`() {
        val hover = module.hoverDocumentation.orEmpty()
        assertTrue("- `requireSourceMatch` (boolean) - default: `true`" in hover, hover)
        assertTrue("- `requireTargetMatch` (boolean) - default: `false`" in hover, hover)
        assertTrue("- `prefixOneOf` (list)" in hover, hover)
    }

    @Test
    fun `empty config produces no findings`() =
        runTest {
            assertTrue(validate(JsonObject(emptyMap()), source = "anything goes").isEmpty())
        }

    @Test
    fun `pattern mismatch error names branch and patterns`() =
        runTest {
            val finding = validate(obj("pattern" to JsonPrimitive("^feature/.+$")), source = "fix-1").single()

            assertEquals(Severity.ERROR, finding.severity)
            assertEquals("Source branch 'fix-1' does not match any allowed pattern.", finding.message)
            assertEquals("Branch naming conventions help identify work type at a glance.", finding.explanation)
        }

    @Test
    fun `allowed patterns take precedence over pattern`() =
        runTest {
            val config = obj("pattern" to JsonPrimitive("^nope$"), "allowedPatterns" to strings("^feature/.+$", "^bugfix/.+$"))

            // the ignored pattern is reported as a warning, and the match itself passes
            assertEquals(listOf(Severity.WARNING), validate(config, source = "bugfix/x").map { it.severity })
        }

    @Test
    fun `target match uses the same patterns`() =
        runTest {
            val config = obj("pattern" to JsonPrimitive("^(main|feature/.+)$"), "requireTargetMatch" to JsonPrimitive(true))

            assertTrue(validate(config).isEmpty())
            val finding = validate(config, target = "release").single()
            assertEquals("Target branch 'release' does not match any allowed pattern.", finding.message)
        }

    @Test
    fun `named branch-prefix pattern resolves with params`() {
        val registry = DefaultPatternRegistry()
        module.registerPatterns(registry)

        val defaultRegex = Regex(assertNotNull(registry.resolve("branch-prefix")))
        assertTrue(defaultRegex.matches("hotfix/a"))
        assertFalse(defaultRegex.matches("feature/"), "prefix alone is not a branch")

        val custom = Regex(assertNotNull(registry.resolve("branch-prefix", mapOf("prefixes" to "a.b/, c/"))))
        assertTrue(custom.matches("a.b/x"))
        assertTrue(custom.matches("c/x"))
        assertFalse(custom.matches("aXb/x"), "prefixes are escaped")
    }

    @Test
    fun `structured prefix rule suggests the allowed prefixes`() =
        runTest {
            val finding = validate(obj("prefixOneOf" to strings("feat/", "fix/")), source = "feature/x").single()

            assertEquals("Source branch 'feature/x' has none of the allowed prefixes.", finding.message)
            assertEquals("Rename the branch to start with one of `feat/`, `fix/`.", finding.suggestion)
        }

    @Test
    fun `structured empty prefix list is ignored`() =
        runTest {
            assertTrue(validate(obj("prefixOneOf" to JsonArray(emptyList())), source = "x").isEmpty())
        }

    @Test
    fun `structured case applies to the body after the prefix`() =
        runTest {
            val config = obj("prefixOneOf" to strings("feat/"), "case" to JsonPrimitive("kebab"))

            assertTrue(validate(config, source = "feat/add-login").isEmpty())
            val finding = validate(config, source = "feat/Add_Login").single()
            assertEquals("Source branch 'feat/Add_Login' body is not kebab-case.", finding.message)
            assertEquals("Rename the branch body to use kebab-case (e.g. my-feature-branch).", finding.suggestion)
        }

    @Test
    fun `structured case without prefix checks the whole branch`() =
        runTest {
            val config = obj("case" to JsonPrimitive("lower"))

            assertTrue(validate(config, source = "feature/abc").isEmpty())
            assertEquals(1, validate(config, source = "Feature/abc").size)
        }

    @Test
    fun `structured max length reports actual length`() =
        runTest {
            val finding = validate(obj("maxLength" to JsonPrimitive(5)), source = "feature/x").single()

            assertEquals("Source branch 'feature/x' exceeds max length 5 (actual: 9).", finding.message)
            assertEquals("Shorten the branch name to at most 5 characters.", finding.suggestion)
        }

    @Test
    fun `structured target check runs only when required`() =
        runTest {
            val config = obj("maxLength" to JsonPrimitive(4), "requireTargetMatch" to JsonPrimitive(true))

            val findings = validate(config, source = "abc", target = "release")

            assertEquals(listOf("Target branch 'release' exceeds max length 4 (actual: 7)."), findings.map { it.message })
        }

    @Test
    fun `structured source check honours require source match false`() =
        runTest {
            val config =
                obj(
                    "maxLength" to JsonPrimitive(4),
                    "requireSourceMatch" to JsonPrimitive(false),
                    "requireTargetMatch" to JsonPrimitive(true),
                )

            val findings = validate(config, source = "a-very-long-source", target = "main")

            assertTrue(findings.isEmpty(), findings.toString())
        }

    @Test
    fun `equally close allowed patterns resolve to the first configured one`() =
        runTest {
            val featureFirst = obj("allowedPatterns" to strings("^feature/.+$", "^bugfix/.+$"))
            val bugfixFirst = obj("allowedPatterns" to strings("^bugfix/.+$", "^feature/.+$"))

            assertEquals(
                "Change the branch name to `feature/login`.",
                validate(featureFirst, source = "login").single().suggestion
            )
            assertEquals(
                "Change the branch name to `bugfix/login`.",
                validate(bugfixFirst, source = "login").single().suggestion
            )
        }
}
