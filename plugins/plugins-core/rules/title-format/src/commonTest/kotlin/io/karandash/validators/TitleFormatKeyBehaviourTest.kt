package io.karandash.validators

import io.karandash.testfixtures.createTestMergeRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TitleFormatKeyBehaviourTest {
    private fun messages(
        config: TitleFormatConfig,
        title: String,
    ): List<String> = TitleFormatValidator(config).validate(createTestMergeRequest(title = title)).map { it.message }

    @Test
    fun `pattern flags a title that does not match`() {
        val config = TitleFormatConfig(pattern = "[A-Z]+-\\d+ .+")
        assertEquals(emptyList(), messages(config, "ABC-1 add login"))
        assertTrue(messages(config, "add login").single().contains("does not match"))
    }

    @Test
    fun `minLength flags a short title`() {
        assertEquals(1, messages(TitleFormatConfig(minLength = 20), "short").size)
        assertEquals(emptyList(), messages(TitleFormatConfig(minLength = 3), "short"))
    }

    @Test
    fun `maxLength flags a long title`() {
        assertEquals(1, messages(TitleFormatConfig(maxLength = 5), "much too long").size)
        assertEquals(emptyList(), messages(TitleFormatConfig(maxLength = 50), "much too long"))
    }

    @Test
    fun `requireTicket flags a title without a ticket`() {
        val config = TitleFormatConfig(requireTicket = true)
        assertTrue(messages(config, "add login").single().contains("no ticket"))
        assertEquals(emptyList(), messages(config, "ABC-12 add login"))
    }

    @Test
    fun `type allowed rejects a conventional type outside the list`() {
        val config = TitleFormatConfig(type = TitleTypeConfig(allowed = listOf("feat")))
        assertEquals(emptyList(), messages(config, "feat: add login"))
        assertEquals(1, messages(config, "fix: add login").size)
    }

    @Test
    fun `type enables the conventional title check`() {
        val config = TitleFormatConfig(type = TitleTypeConfig())
        assertEquals(1, messages(config, "add login").size)
        assertEquals(emptyList(), messages(config, "fix: add login"))
    }

    @Test
    fun `scope required flags a title without a scope`() {
        val config = TitleFormatConfig(scope = TitleScopeConfig(required = true))
        assertEquals(1, messages(config, "feat: add login").size)
        assertEquals(emptyList(), messages(config, "feat(auth): add login"))
    }

    @Test
    fun `scope enables the scope constraint`() {
        val config = TitleFormatConfig(scope = TitleScopeConfig(required = true))
        assertEquals(1, messages(config, "feat: x").size)
    }

    @Test
    fun `subject minLength flags a short subject`() {
        val config = TitleFormatConfig(subject = TitleSubjectConfig(minLength = 10))
        assertEquals(1, messages(config, "feat: short").size)
        assertEquals(emptyList(), messages(config, "feat: a long enough subject"))
    }

    @Test
    fun `subject noPeriod flags a title ending in a period`() {
        val config = TitleFormatConfig(subject = TitleSubjectConfig(noPeriod = true))
        assertTrue(messages(config, "Add login.").single().contains("period"))
        assertEquals(emptyList(), messages(config, "Add login"))
    }

    @Test
    fun `subject enables the subject constraints`() {
        val config = TitleFormatConfig(subject = TitleSubjectConfig(noPeriod = true))
        assertEquals(1, messages(config, "Add login.").size)
    }
}
