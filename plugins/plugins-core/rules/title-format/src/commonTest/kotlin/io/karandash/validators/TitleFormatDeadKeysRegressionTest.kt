package io.karandash.validators

import io.karandash.testfixtures.createTestMergeRequest
import kotlin.test.Test
import kotlin.test.assertEquals

/** scope.required and subject.minLength work without a `type` block. */
class TitleFormatDeadKeysRegressionTest {
    private fun findings(
        config: TitleFormatConfig,
        title: String,
    ) = TitleFormatValidator(config).validate(createTestMergeRequest(title = title))

    @Test
    fun `scope required applies without type`() {
        val config = TitleFormatConfig(scope = TitleScopeConfig(required = true))
        assertEquals(1, findings(config, "fix: a thing").size)
        assertEquals(0, findings(config, "fix(core): a thing").size)
    }

    @Test
    fun `subject minLength applies without type`() {
        val config = TitleFormatConfig(subject = TitleSubjectConfig(minLength = 10))
        assertEquals(1, findings(config, "fix: short").size)
        assertEquals(0, findings(config, "fix: long enough subject").size)
    }

    @Test
    fun `subject noPeriod alone keeps free form titles valid`() {
        val config = TitleFormatConfig(subject = TitleSubjectConfig(noPeriod = true))
        assertEquals(0, findings(config, "Free form title").size)
    }
}
