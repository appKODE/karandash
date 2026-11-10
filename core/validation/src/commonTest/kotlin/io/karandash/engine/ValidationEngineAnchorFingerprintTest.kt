package io.karandash.engine

import io.karandash.api.ModuleRegistryImpl
import io.karandash.api.PluginContext
import io.karandash.api.module.RuleModule
import io.karandash.config.Config
import io.karandash.config.RulesConfig
import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import io.karandash.models.support.WellKnownMetadataKeys
import io.karandash.utils.finding.AnchorFingerprint
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ValidationEngineAnchorFingerprintTest {
    private class LineRule : RuleModule {
        override val id = "line-rule"
        override val name = id
        override val version = "1.0"
        override val dslName = id

        override suspend fun contributedRules() = setOf("lineRule")

        override suspend fun initialize(context: PluginContext) = true

        override suspend fun validate(
            ruleId: String,
            mr: MergeRequest,
            configElement: JsonElement,
        ) = listOf(
            Finding(Severity.WARNING, "on added", ruleId, Location.FileRef("a.kt", 2)),
            Finding(Severity.WARNING, "file level", ruleId, Location.FileRef("a.kt")),
        )
    }

    @Test
    fun `engine stamps the anchor fingerprint on line findings only`() =
        runTest {
            val registry = ModuleRegistryImpl().apply { register(LineRule()) }
            val config = Config(rules = RulesConfig(ruleConfigs = mapOf("lineRule" to JsonObject(emptyMap()))))
            val engine = ValidationEngine(config, registry, configExtractor = { _, _ -> listOf(JsonObject(emptyMap())) })

            val byMessage = engine.validate(mr()).findings.associateBy { it.message }

            assertEquals(
                AnchorFingerprint.generate("lineRule", "a.kt", "val y = 2"),
                byMessage.getValue("on added").metadata[WellKnownMetadataKeys.ANCHOR_FINGERPRINT],
            )
            assertNull(byMessage.getValue("file level").metadata[WellKnownMetadataKeys.ANCHOR_FINGERPRINT])
        }

    private fun mr() =
        MergeRequest(
            id = "1",
            iid = 1,
            title = "feat: x",
            description = null,
            author = User(username = "alice", name = "Alice"),
            sourceBranch = "feature/x",
            targetBranch = "main",
            state = MergeRequestState.OPENED,
            changes = listOf(
                FileChange("a.kt", ChangeType.MODIFIED, diffContent = "@@ -1,1 +1,2 @@\n val x = 1\n+val y = 2"),
            ),
            commits = emptyList(),
            approvals = emptyList(),
            reviewers = emptyList(),
            draft = false,
            labels = emptyList(),
        )
}
