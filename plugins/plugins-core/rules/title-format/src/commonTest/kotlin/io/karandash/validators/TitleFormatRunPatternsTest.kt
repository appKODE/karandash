package io.karandash.validators

import io.karandash.api.ExperimentalKarandashApi
import io.karandash.api.ModuleRegistryImpl
import io.karandash.api.run.RunServiceKeys
import io.karandash.helpers.RegexHelper
import io.karandash.logger.NoOpLogger
import io.karandash.models.support.PatternAwareCompiler
import io.karandash.testfixtures.createTestMergeRequest
import io.karandash.testfixtures.toJsonElement
import io.karandash.utils.BoundedCache
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Two overlapping runs define `@type` differently; each run's validator resolves its own definition. */
@OptIn(ExperimentalKarandashApi::class)
class TitleFormatRunPatternsTest {
    private val runA = compilerFor("^feat: .*")
    private val runB = compilerFor("^fix: .*")
    private val config = TitleFormatConfig(pattern = "@type")

    @AfterTest
    fun tearDown() = RegexHelper.clearPatternCompiler()

    @Test
    fun validatorUsesItsRunCompilerNotTheNewestInFlight() {
        RegexHelper.injectPatternCompiler(runA)
        RegexHelper.injectPatternCompiler(runB)
        val cache = BoundedCache<String, Regex>(maxEntries = 4)

        val a = TitleFormatValidator(config, cache, runA).validate(createTestMergeRequest(title = "feat: x"))
        val b = TitleFormatValidator(config, cache, runB).validate(createTestMergeRequest(title = "feat: x"))

        assertTrue(a.isEmpty(), "run A resolved run B's @type: $a")
        assertEquals(1, b.size, "run B reused run A's cached @type: $b")
    }

    @Test
    fun moduleTakesTheCompilerFromTheRunSession() =
        runTest {
            RegexHelper.injectPatternCompiler(runA)
            RegexHelper.injectPatternCompiler(runB)
            val session = ModuleRegistryImpl().forRun(NoOpLogger)
            session.provide(RunServiceKeys.PATTERN_COMPILER, runA)

            val findings = withContext(session.services) {
                TitleFormatRuleModule().validate(
                    TitleFormatRuleModule.RULE_ID,
                    createTestMergeRequest(title = "feat: x"),
                    config.toJsonElement(),
                )
            }

            assertTrue(findings.isEmpty(), "$findings")
        }

    private fun compilerFor(regex: String) = PatternAwareCompiler { pattern, _, _ -> if (pattern == "@type") Regex(regex) else null }
}
