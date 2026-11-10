package io.karandash.validators

import io.karandash.sdk.config.BlockMode
import io.karandash.testfixtures.createTestMergeRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DraftKeyBehaviourTest {
    private fun messages(
        config: DraftConfig,
        title: String = "Add login",
        draft: Boolean = false,
    ): List<String> = DraftValidator(config).validate(createTestMergeRequest(title = title, draft = draft)).map { it.message }

    @Test
    fun `blockDraft false lets a draft MR through`() {
        assertTrue(messages(DraftConfig(blockDraft = BlockMode.FAIL), draft = true).single().contains("draft"))
        assertEquals(emptyList(), messages(DraftConfig(blockDraft = BlockMode.OFF), draft = true))
    }

    @Test
    fun `wipPatterns flags a title that matches a custom pattern`() {
        val config = DraftConfig(wipPatterns = listOf("DONTMERGE"))
        assertTrue(messages(config, title = "DONTMERGE login").single().contains("WIP indicator"))
        assertEquals(emptyList(), messages(config, title = "WIP login"))
    }

    @Test
    fun `wipMarkers flags a title that contains a marker`() {
        val config = DraftConfig(wipMarkers = listOf("HOLD"))
        assertTrue(messages(config, title = "HOLD login").single().contains("WIP indicator"))
        assertEquals(emptyList(), messages(config, title = "WIP login"))
    }
}
