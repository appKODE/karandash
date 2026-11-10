package io.karandash.contracts.fw

import io.karandash.contracts.support.createFwTempDir
import io.karandash.contracts.support.parseYamlConfig
import io.karandash.contracts.support.runFwPipeline
import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.models.support.KarandashJson
import io.karandash.patterns.DelimiterType
import io.karandash.testfixtures.createTestEngine
import io.karandash.testfixtures.createTestMergeRequest
import io.karandash.validators.ContextTagConfig
import io.karandash.validators.SizeLimitsRuleModule
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Bad rule config silently decodes as all-defaults (the former `deserializeConfigSafe`),
 * documented uppercase enum values (`delimiter: PARENS`) are coerced to null by `KarandashJson.Default`,
 * and E0007 unknown top-level key is documented but never raised.
 */
class SilentConfigFallbackContractTest {
    @Test
    fun `documented uppercase enum value PARENS decodes to PARENS`() {
        val element = JsonObject(mapOf("delimiter" to JsonPrimitive("PARENS")))
        val config = KarandashJson.Default.decodeFromJsonElement(ContextTagConfig.serializer(), element)
        assertEquals(DelimiterType.PARENS, config.delimiter, "`delimiter: PARENS` (as documented) coerced to null")
    }

    @Test
    fun `invalid rule config value is reported instead of silently using defaults`() =
        runTest {
            val config = parseYamlConfig(
                """
                rules:
                  size-limits:
                    max-lines: lots
                """,
            )
            val mr = createTestMergeRequest(changes = listOf(FileChange("A.kt", ChangeType.MODIFIED, additions = BIG_CHANGE)))
            val result = createTestEngine(config).validate(mr)
            assertTrue(
                result.findings.any { it.rule == SizeLimitsRuleModule.RULE_ID || "maxLines" in it.message || "max-lines" in it.message },
                "`max-lines: lots` silently decoded as defaults; no config-error finding. Findings: ${result.findings}",
            )
        }

    @Test
    fun `unknown top-level config key warns`() =
        runTest {
            val run = runFwPipeline(
                dir = createFwTempDir(),
                configYaml = """
                    rulez:
                      conflict: {}
                """,
            )
            assertTrue(
                run.logger.warnings.any { "rulez" in it },
                "unknown top-level key `rulez` accepted silently (E0007 never raised). Warnings: ${run.logger.warnings}",
            )
        }

    private companion object {
        const val BIG_CHANGE = 50
    }
}
