package io.karandash.plugins.ai.core.rules

import kotlin.test.Test
import kotlin.test.assertEquals

class AiRuleAnalyzerUtilsNormalizePathTest {
    private val known = setOf("src/A.kt")

    @Test
    fun strips_leading_slash_dot_and_diff_prefixes() {
        listOf("/src/A.kt", "./src/A.kt", "a/src/A.kt", "b/src/A.kt").forEach {
            assertEquals("src/A.kt", AiRuleAnalyzerUtils.normalizeDiffPath(it, known))
        }
    }

    @Test
    fun unknown_path_and_null_set_pass_through() {
        assertEquals("other.kt", AiRuleAnalyzerUtils.normalizeDiffPath("other.kt", known))
        assertEquals("/x.kt", AiRuleAnalyzerUtils.normalizeDiffPath("/x.kt", null))
    }
}
