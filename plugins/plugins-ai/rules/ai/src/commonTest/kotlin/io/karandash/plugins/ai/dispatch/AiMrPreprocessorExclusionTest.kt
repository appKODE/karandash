package io.karandash.plugins.ai.dispatch

import io.karandash.api.support.mapToJsonElement
import io.karandash.logger.NoOpLogger
import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.plugins.ai.core.DiffPreprocessorOptions
import io.karandash.plugins.ai.core.rules.AiPartialReviewPolicy
import io.karandash.plugins.ai.core.rules.AiRuleConfig
import io.karandash.state.snapshot.DiffDeltaCalculator
import io.karandash.state.snapshot.SnapshotManager
import io.karandash.state.snapshot.SnapshotPersistence
import io.karandash.testfixtures.createTestMergeRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** What preprocessing leaves out of a rule's diff is named with its reason, so the coverage ledger can list it. */
class AiMrPreprocessorExclusionTest {
    private fun change(
        path: String,
        lines: Int = 1,
        diff: String = "@@ -1 +1 @@\n+x",
        type: ChangeType = ChangeType.MODIFIED,
    ) = FileChange(path = path, changeType = type, additions = lines, diffContent = diff)

    private fun ready(
        mr: MergeRequest,
        config: AiRuleConfig = AiRuleConfig(),
        snapshots: SnapshotManager = SnapshotManager(persistence = null, mrKey = "mr", logger = NoOpLogger),
    ) = assertIs<PreprocessResult.Ready>(AiMrPreprocessor(NoOpLogger).preprocessMrForRule("rule", mr, config, snapshots))

    @Test
    fun `lock vendor and asset files are excluded by default and named with the reason`() {
        val result = ready(
            createTestMergeRequest(
                changes = listOf(
                    change("src/App.kt"),
                    change("Cargo.lock"),
                    change("vendor/lib/x.go"),
                    change("web/node_modules/a/index.js"),
                    change("res/logo.svg"),
                    change("go.sum"),
                ),
            ),
        )

        assertEquals(listOf("src/App.kt"), result.mr.changes.map { it.path })
        assertEquals(
            setOf("Cargo.lock", "vendor/lib/x.go", "web/node_modules/a/index.js", "res/logo.svg", "go.sum"),
            result.excluded.keys,
        )
        assertEquals("matched **/*.lock", result.excluded["Cargo.lock"])
    }

    @Test
    fun `a configured exclude list keeps the asset and generated defaults`() {
        val config = AiRuleConfig.fromPluginConfig(
            mapToJsonElement(mapOf("size-policy" to mapOf("exclude" to mapOf("patterns" to listOf("**/*.md"))))),
        )
        val result = ready(
            createTestMergeRequest(
                changes = listOf(
                    change("src/App.swift"),
                    change("README.md"),
                    change("res/icon.svg"),
                    change("App/Assets.xcassets/a.imageset/Contents.json"),
                    change("Foo/Generated/Bar.swift"),
                ),
            ),
            config,
        )

        assertEquals(listOf("src/App.swift"), result.mr.changes.map { it.path })
    }

    @Test
    fun `vendored code is excluded only under a root vendor or third_party directory`() {
        val result = ready(
            createTestMergeRequest(
                changes = listOf(
                    change("vendor/Foo.kt"),
                    change("third_party/zlib/inflate.c"),
                    change("src/main/kotlin/com/acme/vendor/VendorService.kt"),
                ),
            ),
        )

        assertEquals(listOf("src/main/kotlin/com/acme/vendor/VendorService.kt"), result.mr.changes.map { it.path })
        assertEquals("matched vendor/**", result.excluded["vendor/Foo.kt"])
        assertEquals("matched third_party/**", result.excluded["third_party/zlib/inflate.c"])
    }

    @Test
    fun `preprocessor drops and partial review drops are excluded with their reason`() {
        val result = ready(
            createTestMergeRequest(
                changes = listOf(
                    change("src/A.kt", lines = 30),
                    change("src/B.kt", lines = 2),
                    change("src/Gone.kt", diff = "@@ -1 +0,0 @@\n-x", type = ChangeType.DELETED),
                ),
            ),
            AiRuleConfig(
                partialReviewPolicy = AiPartialReviewPolicy(maxFiles = 1),
                diffPreprocessor = DiffPreprocessorOptions(skipDeletedFiles = true),
            ),
        )

        assertEquals(listOf("src/A.kt"), result.mr.changes.map { it.path })
        assertEquals(mapOf("src/Gone.kt" to "deleted", "src/B.kt" to "partial review: over 1 files"), result.excluded)
    }

    @Test
    fun `partial review picks among changed files after the delta not before`() {
        val big = change("src/Big.kt", lines = 100)
        val small = change("src/Small.kt", lines = 1, diff = "@@ -1 +1 @@\n+new")
        val mr = createTestMergeRequest(changes = listOf(big, small))
        val previous = DiffDeltaCalculator.snapshot(mr.copy(changes = listOf(big, small.copy(diffContent = "@@ -1 +1 @@\n+old"))))
        val persistence = object : SnapshotPersistence {
            override fun loadSnapshot(mrKey: String) = previous

            override fun saveSnapshot(
                mrKey: String,
                snapshot: Map<String, Long>,
            ) = Unit
        }
        val snapshots = SnapshotManager(persistence, mrKey = "mr", logger = NoOpLogger).also { it.load() }

        val result = ready(mr, AiRuleConfig(partialReviewPolicy = AiPartialReviewPolicy(maxFiles = 1)), snapshots)

        assertEquals(listOf("src/Small.kt"), result.mr.changes.map { it.path })
    }
}
