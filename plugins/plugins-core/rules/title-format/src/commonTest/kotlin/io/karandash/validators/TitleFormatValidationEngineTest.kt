package io.karandash.validators

import io.karandash.config.Config
import io.karandash.config.RulesConfig
import io.karandash.models.finding.Severity
import io.karandash.testfixtures.createTestEngine
import io.karandash.testfixtures.createTestMergeRequest
import io.karandash.testfixtures.toJsonElement
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TitleFormatValidationEngineTest {
    @Test
    fun `validateTitle with invalid regex pattern returns error finding`() =
        runTest {
            val config = Config(
                rules = RulesConfig(
                    ruleConfigs = mapOf(
                        "titleFormat" to TitleFormatConfig(pattern = "[invalid regex((").toJsonElement()
                    )
                )
            )
            val mr = createTestMergeRequest(title = "feat: test")
            val engine = createTestEngine(config)

            val result = engine.validate(mr)
            assertEquals(1, result.findings.size)
            assertEquals(Severity.ERROR, result.findings[0].severity)
            assertEquals("titleFormat", result.findings[0].rule)
            assertTrue(result.findings[0].message.contains("Invalid regex pattern"))
        }

    @Test
    fun `validateTitle with valid pattern matching title produces no findings`() =
        runTest {
            val config = Config(
                rules = RulesConfig(
                    ruleConfigs = mapOf(
                        "titleFormat" to TitleFormatConfig(pattern = "^(feat|fix): .*").toJsonElement()
                    )
                )
            )
            val mr = createTestMergeRequest(title = "feat: add new feature")
            val engine = createTestEngine(config)
            val result = engine.validate(mr)

            assertTrue(result.findings.isEmpty())
        }

    @Test
    fun `validateTitle with pattern not matching title produces error finding`() =
        runTest {
            val config = Config(
                rules = RulesConfig(
                    ruleConfigs = mapOf(
                        "titleFormat" to TitleFormatConfig(pattern = "^(feat|fix): .*").toJsonElement()
                    )
                )
            )
            val mr = createTestMergeRequest(title = "invalid title format")
            val engine = createTestEngine(config)
            val result = engine.validate(mr)

            assertEquals(1, result.findings.size)
            assertEquals(Severity.ERROR, result.findings[0].severity)
            assertEquals("titleFormat", result.findings[0].rule)
            assertTrue(result.findings[0].message.contains("does not match required pattern"))
        }

    @Test
    fun `validateTitle with minLength of 0 and empty title produces no length finding`() =
        runTest {
            val config = Config(
                rules = RulesConfig(
                    ruleConfigs = mapOf(
                        "titleFormat" to TitleFormatConfig(pattern = ".*", minLength = 0).toJsonElement()
                    )
                )
            )
            val mr = createTestMergeRequest(title = "")
            val engine = createTestEngine(config)
            val result = engine.validate(mr)

            // Should have no length violation (but will fail pattern match because empty string)
            val lengthFindings = result.findings.filter { it.message.contains("too short") }
            assertTrue(lengthFindings.isEmpty())
        }

    @Test
    fun `validateTitle with maxLength of 0 and any title produces warning`() =
        runTest {
            val config = Config(
                rules = RulesConfig(
                    ruleConfigs = mapOf(
                        "titleFormat" to TitleFormatConfig(pattern = ".*", maxLength = 0).toJsonElement()
                    )
                )
            )
            val mr = createTestMergeRequest(title = "X")
            val engine = createTestEngine(config)
            val result = engine.validate(mr)

            val maxLengthFindings = result.findings.filter { it.message.contains("too long") }
            assertEquals(1, maxLengthFindings.size)
            assertEquals(Severity.WARNING, maxLengthFindings[0].severity)
        }

    @Test
    fun `validateTitle with title exactly at minLength boundary passes`() =
        runTest {
            val config = Config(
                rules = RulesConfig(
                    ruleConfigs = mapOf(
                        "titleFormat" to TitleFormatConfig(pattern = ".*", minLength = 10).toJsonElement()
                    )
                )
            )
            val mr = createTestMergeRequest(title = "1234567890") // Exactly 10 chars
            val engine = createTestEngine(config)
            val result = engine.validate(mr)

            val lengthFindings = result.findings.filter { it.message.contains("too short") }
            assertTrue(lengthFindings.isEmpty())
        }

    @Test
    fun `validateTitle with title exactly at maxLength boundary passes`() =
        runTest {
            val config = Config(
                rules = RulesConfig(
                    ruleConfigs = mapOf(
                        "titleFormat" to TitleFormatConfig(pattern = ".*", maxLength = 10).toJsonElement()
                    )
                )
            )
            val mr = createTestMergeRequest(title = "1234567890") // Exactly 10 chars
            val engine = createTestEngine(config)
            val result = engine.validate(mr)

            val lengthFindings = result.findings.filter { it.message.contains("too long") }
            assertTrue(lengthFindings.isEmpty())
        }

    @Test
    fun `validateTitle with title at maxLength plus one produces warning`() =
        runTest {
            val config = Config(
                rules = RulesConfig(
                    ruleConfigs = mapOf(
                        "titleFormat" to TitleFormatConfig(pattern = ".*", maxLength = 10).toJsonElement()
                    )
                )
            )
            val mr = createTestMergeRequest(title = "12345678901") // 11 chars
            val engine = createTestEngine(config)
            val result = engine.validate(mr)

            val lengthFindings = result.findings.filter { it.message.contains("too long") }
            assertEquals(1, lengthFindings.size)
            assertEquals(Severity.WARNING, lengthFindings[0].severity)
            assertTrue(lengthFindings[0].message.contains("max: 10"))
            assertTrue(lengthFindings[0].message.contains("actual: 11"))
        }

    @Test
    fun `validateTitle with empty string title`() =
        runTest {
            val config = Config(
                rules = RulesConfig(
                    ruleConfigs = mapOf(
                        "titleFormat" to TitleFormatConfig(pattern = ".+", minLength = 1).toJsonElement()
                    )
                )
            )
            val mr = createTestMergeRequest(title = "")
            val engine = createTestEngine(config)
            val result = engine.validate(mr)

            // Should fail both pattern and minLength
            assertTrue(result.findings.size >= 2)
            assertTrue(result.findings.any { it.message.contains("does not match required pattern") })
            assertTrue(result.findings.any { it.message.contains("too short") })
        }

    @Test
    fun `validateTitle with only whitespace title`() =
        runTest {
            val config = Config(
                rules = RulesConfig(
                    ruleConfigs = mapOf(
                        "titleFormat" to TitleFormatConfig(pattern = "^(feat|fix): .*").toJsonElement()
                    )
                )
            )
            val mr = createTestMergeRequest(title = "   ")
            val engine = createTestEngine(config)
            val result = engine.validate(mr)

            assertEquals(1, result.findings.size)
            assertEquals(Severity.ERROR, result.findings[0].severity)
            assertTrue(result.findings[0].message.contains("does not match required pattern"))
        }

    @Test
    fun `validateTitle with very long title over 10000 chars`() =
        runTest {
            val config = Config(
                rules = RulesConfig(
                    ruleConfigs = mapOf(
                        "titleFormat" to TitleFormatConfig(pattern = ".*", maxLength = 100).toJsonElement()
                    )
                )
            )
            val longTitle = "X".repeat(10000)
            val mr = createTestMergeRequest(title = longTitle)
            val engine = createTestEngine(config)
            val result = engine.validate(mr)

            val lengthFindings = result.findings.filter { it.message.contains("too long") }
            assertEquals(1, lengthFindings.size)
            assertTrue(lengthFindings[0].message.contains("max: 100"))
            assertTrue(lengthFindings[0].message.contains("actual: 10000"))
        }

    @Test
    fun `validateTitle with special characters including newlines`() =
        runTest {
            val config = Config(
                rules = RulesConfig(
                    ruleConfigs = mapOf(
                        "titleFormat" to TitleFormatConfig(pattern = ".*").toJsonElement()
                    )
                )
            )
            val mr = createTestMergeRequest(title = "Title with\nnewline\tand\ttabs")
            val engine = createTestEngine(config)
            val result = engine.validate(mr)

            // NOTE: Pattern .* does NOT match newlines in Kotlin by default
            // This test documents that titles with newlines will fail the pattern match
            val patternFindings = result.findings.filter { it.message.contains("does not match required pattern") }
            assertEquals(1, patternFindings.size)
        }

    @Test
    fun `validateTitle with unicode and emoji characters`() =
        runTest {
            val config = Config(
                rules = RulesConfig(
                    ruleConfigs = mapOf(
                        "titleFormat" to TitleFormatConfig(pattern = ".*").toJsonElement()
                    )
                )
            )
            val mr = createTestMergeRequest(title = "feat: Add \uD83C\uDF89 celebration feature \u4e2d\u6587")
            val engine = createTestEngine(config)
            val result = engine.validate(mr)

            // Should handle unicode/emoji without crashing
            val patternFindings = result.findings.filter { it.message.contains("does not match required pattern") }
            assertTrue(patternFindings.isEmpty())
        }
}
