package io.karandash.plugins.ai.dispatch

import io.karandash.api.ai.AiContextBlock
import io.karandash.api.ai.BlockBudget
import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.plugins.ai.core.AiPluginConfig
import io.karandash.plugins.ai.core.TokenBudget
import io.karandash.plugins.ai.core.TokenUnit
import io.karandash.plugins.ai.core.context.DiffAnnotationsEnricher
import io.karandash.plugins.ai.core.rules.AiRuleConfig
import io.karandash.plugins.ai.providers.AiProvider
import io.karandash.plugins.ai.providers.AiResponse
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.sync.Semaphore
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChunkDiffNotesTest {
    private val all = listOf(guarded("A.kt"), guarded("B.kt"), guarded("C.kt"))
    private val planned = wholeMrBlock(all)

    @Test
    fun `a chunk call gets the notes on its own files only`() {
        val chunk = ChunkDiffNotes.rescope(input(all.take(1), listOf(planned), plannedTokens = TokenUnit.tokensOf(planned.content)))

        val notes = chunk.contextBlocks.single().content
        assertTrue("Removed guard at A.kt" in notes, notes)
        assertFalse("B.kt" in notes || "C.kt" in notes, notes)
        assertTrue(notes.startsWith("#### Diff notes\n<untrusted-content source=\"diff-annotations\">"), notes)
        assertEquals(TokenUnit.tokensOf(notes), chunk.plannedContextTokens)
    }

    @Test
    fun `a chunk with nothing to note drops the block`() {
        val plain = FileChange("D.kt", ChangeType.MODIFIED, diffContent = "@@ -1,1 +1,1 @@\n-old\n+new")

        val chunk = ChunkDiffNotes.rescope(input(listOf(plain), listOf(planned), plannedTokens = TokenUnit.tokensOf(planned.content)))

        assertTrue(chunk.contextBlocks.isEmpty())
        assertEquals(0, chunk.plannedContextTokens)
    }

    @Test
    fun `a rebuilt block never outgrows the planned one`() {
        val small = wholeMrBlock(all.take(1))

        val chunk = ChunkDiffNotes.rescope(input(all, listOf(small), plannedTokens = 0))

        assertTrue(
            chunk.contextBlocks
                .single()
                .content.length <= small.content.length
        )
    }

    // The block as the assembler plans it: heading and untrusted wrapper inside its content.
    private fun wholeMrBlock(changes: List<FileChange>): AiContextBlock {
        val block = checkNotNull(DiffAnnotationsEnricher.blockFor(changes, BlockBudget.UNLIMITED))
        return AiContextBlock("", wrapUntrusted(block), block.priority, DiffAnnotationsEnricher.MODULE_ID)
    }

    private fun guarded(path: String) =
        FileChange(path, ChangeType.MODIFIED, diffContent = "@@ -1,2 +1,1 @@\n-    require(user.id > 0)\n     save(user)")

    private fun input(
        changes: List<FileChange>,
        blocks: List<AiContextBlock>,
        plannedTokens: Int,
    ) = AiRuleDispatcher.DispatchInput(
        ruleId = "review-comments",
        mr = createTestMergeRequest(changes = changes),
        concurrencySemaphore = Semaphore(1),
        aiProvider = UnusedProvider,
        budget = TokenBudget(maxTokens = 100_000),
        model = "m",
        config = JsonObject(emptyMap()),
        resolvedPrompt = null,
        aiService = null,
        aiRuleConfig = AiRuleConfig(),
        pluginCfg = AiPluginConfig(),
        contextProcessors = emptyList(),
        accumulatedFindings = emptyList(),
        customRules = emptyList(),
        plannedContextTokens = plannedTokens,
        contextBlocks = blocks,
    )

    private object UnusedProvider : AiProvider {
        override val providerId = "unused"

        override suspend fun complete(
            prompt: String,
            systemPrompt: String?,
            model: String,
            maxTokens: Int,
            temperature: Double,
            jsonMode: Boolean,
        ): AiResponse = error("not called")

        override suspend fun close() = Unit
    }
}
