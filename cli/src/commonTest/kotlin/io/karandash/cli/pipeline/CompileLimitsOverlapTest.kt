package io.karandash.cli.pipeline

import io.karandash.config.Config
import io.karandash.config.LimitsConfig
import io.karandash.config.PatternLimitsConfig
import io.karandash.config.RegexLimitsConfig
import io.karandash.helpers.SafeRegexCompiler
import io.karandash.patterns.NamedPatternExpander
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Overlapping runs share the process-wide compile limits: the strictest active run wins, undo restores the rest. */
class CompileLimitsOverlapTest {
    private fun config(
        patternLength: Int,
        depth: Int,
    ) = Config(
        limits = LimitsConfig(
            regex = RegexLimitsConfig(maxPatternLength = patternLength),
            patterns = PatternLimitsConfig(maxExpansionDepth = depth),
        ),
    )

    private fun compilesPatternOfLength(length: Int): Boolean =
        SafeRegexCompiler.Default.compile("a".repeat(length)) !is SafeRegexCompiler.CompileResult.TooLong

    @Test
    fun `strictest active run wins and each undo restores the remaining runs`() =
        runTest {
            val undoStrict = PipelineInfrastructure.installCompileLimits(config(patternLength = 100, depth = 6))
            val undoLoose = PipelineInfrastructure.installCompileLimits(config(patternLength = 300, depth = 9))

            // the later, looser run does not loosen the earlier one
            assertEquals(6, NamedPatternExpander.maxDepth)
            assertEquals(false, compilesPatternOfLength(200))

            undoStrict()
            assertEquals(9, NamedPatternExpander.maxDepth)
            assertEquals(true, compilesPatternOfLength(200))

            undoLoose()
            assertEquals(NamedPatternExpander.MAX_DEPTH, NamedPatternExpander.maxDepth)
            assertEquals(true, compilesPatternOfLength(SafeRegexCompiler.MAX_PATTERN_LENGTH))
            assertEquals(false, compilesPatternOfLength(SafeRegexCompiler.MAX_PATTERN_LENGTH + 1))
        }

    private fun compileNested() = SafeRegexCompiler.Default.compile("((a)+)+")

    @Test
    fun `limits regex maxQuantifierNesting decides whether nested quantifiers compile`() =
        runTest {
            fun nesting(depth: Int) = Config(limits = LimitsConfig(regex = RegexLimitsConfig(maxQuantifierNesting = depth)))

            val undoDefault = PipelineInfrastructure.installCompileLimits(Config())
            assertIs<SafeRegexCompiler.CompileResult.NestedQuantifiers>(compileNested())
            undoDefault()

            val undoLoose = PipelineInfrastructure.installCompileLimits(nesting(2))
            assertIs<SafeRegexCompiler.CompileResult.Success>(compileNested())
            undoLoose()

            assertIs<SafeRegexCompiler.CompileResult.NestedQuantifiers>(compileNested())
        }
}
