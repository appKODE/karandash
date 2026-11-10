package io.karandash.plugins.ai.dispatch

import io.karandash.logger.Logger
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import io.karandash.platform.writeText
import io.karandash.plugins.ai.buildTestRegistry
import io.karandash.plugins.ai.core.AiPluginConfig
import io.karandash.plugins.ai.core.FeedbackStore
import io.karandash.plugins.ai.core.ReviewStrictness
import io.karandash.plugins.ai.core.rules.AiRuleConfig
import io.karandash.sdk.content.ContentSource
import io.karandash.testfixtures.TestFiles
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AiPromptAssemblerFileInstructionsTest {
    private val warnings = mutableListOf<String>()
    private val logger = object : Logger {
        override fun debug(message: String) = Unit

        override fun info(message: String) = Unit

        override fun warn(message: String) {
            warnings += message
        }

        override fun error(message: String) = Unit
    }

    private val mr = MergeRequest(
        id = "1",
        iid = 1,
        title = "t",
        description = null,
        sourceBranch = "f",
        targetBranch = "main",
        author = User("1", "dev", "Dev"),
        state = MergeRequestState.OPENED,
        draft = false,
        labels = emptyList(),
        commits = emptyList(),
        changes = emptyList(),
    )

    private fun build(cfg: AiPluginConfig): String? =
        AiPromptAssembler(buildTestRegistry(), logger).buildExtraInstructions(
            ruleId = "review-comments",
            mr = mr,
            pluginCfg = cfg,
            aiRuleConfig = AiRuleConfig(),
            strictness = ReviewStrictness.STANDARD,
            feedbackStore = FeedbackStore(),
            needsScopeDetection = { false },
        )

    @Test
    fun `file based extraInstructions and bestPractices are read into the prompt`() {
        val dir = TestFiles.createTestTempDirectory("karandash-assembler")
        SystemFileSystem.createDirectories(dir)
        val extra = Path(dir, "extra.md")
        val best = Path(dir, "best.md")
        extra.writeText("EXTRA-FROM-FILE")
        best.writeText("BEST-FROM-FILE")
        try {
            val prompt = assertNotNull(
                build(
                    AiPluginConfig(
                        extraInstructions = ContentSource.LocalFile(extra.toString()),
                        bestPractices = ContentSource.LocalFile(best.toString()),
                    ),
                ),
            )
            assertTrue("EXTRA-FROM-FILE" in prompt, prompt)
            assertTrue("BEST-FROM-FILE" in prompt, prompt)
        } finally {
            SystemFileSystem.delete(extra)
            SystemFileSystem.delete(best)
            SystemFileSystem.delete(dir)
        }
    }

    @Test
    fun `missing instructions file warns and does not throw`() {
        val missing = Path(TestFiles.createTestTempDirectory("karandash-no-such-file"), "absent.md").toString()
        val prompt = build(AiPluginConfig(extraInstructions = ContentSource.LocalFile(missing)))
        assertTrue(prompt == null || missing !in prompt, prompt)
        assertTrue(warnings.any { "extraInstructions" in it && missing in it }, warnings.toString())
    }
}
