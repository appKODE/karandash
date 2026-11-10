package io.karandash.profiles

import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import io.karandash.models.Approval
import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import io.karandash.models.finding.Severity
import io.karandash.models.support.KarandashJson
import io.karandash.validators.FileChangesConfig
import io.karandash.validators.FileChangesValidator
import io.karandash.validators.SecurityConfig
import io.karandash.validators.SizeLimitsConfig
import io.karandash.validators.TitleFormatConfig
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SecurityConsciousProfileModuleTest {
    private val module = SecurityConsciousProfileModule()

    private class TestContext : PluginContext {
        override val pluginConfig: JsonElement = JsonObject(emptyMap())
        override val hostVersion: String = "test"
        override val workingDirectory: String = "/tmp"

        override fun log(message: String) {}

        override fun warn(message: String) {}

        override fun env(name: String): String? = null
    }

    @Test
    fun profileIdMatchesExpected() {
        assertEquals("security-conscious", module.profileId)
    }

    @Test
    fun typeIsProfile() {
        assertEquals(ModuleType.PROFILE, module.type)
    }

    @Test
    fun ruleConfigsIsNotEmpty() {
        runTest {
            assertTrue(module.ruleConfigs().isNotEmpty())
        }
    }

    @Test
    fun initializeReturnsTrue() =
        runTest {
            assertTrue(module.initialize(TestContext()))
        }

    @Test
    fun summaryIsNotBlank() {
        assertTrue(module.summary.isNotBlank())
    }

    @Test
    fun ruleConfigsRoundTripThroughConfigSpec() {
        runTest {
            val configs = module.ruleConfigs()

            val securityConfig = KarandashJson.Default.decodeFromJsonElement(SecurityConfig.serializer(), configs.getValue("security"))
            assertEquals(true, securityConfig.scanForSecrets)

            val fileChangesConfig = KarandashJson.Default.decodeFromJsonElement(
                FileChangesConfig.serializer(),
                configs.getValue("fileChanges")
            )
            assertEquals(6, fileChangesConfig.patterns.size)

            val sizeConfig = KarandashJson.Default.decodeFromJsonElement(SizeLimitsConfig.serializer(), configs.getValue("sizeLimits"))
            assertEquals(400, sizeConfig.maxLines)
            assertEquals(15, sizeConfig.maxCommits)

            val titleConfig = KarandashJson.Default.decodeFromJsonElement(TitleFormatConfig.serializer(), configs.getValue("titleFormat"))
            assertEquals(10, titleConfig.minLength)
        }
    }

    @Test
    fun blockedFileChangeYieldsFindingUntilSecurityReviewLabelIsAdded() =
        runTest {
            val config = KarandashJson.Default.decodeFromJsonElement(
                FileChangesConfig.serializer(),
                module.ruleConfigs().getValue("fileChanges"),
            )
            val validator = FileChangesValidator(config)
            val mr = mergeRequest(path = "app/.env", labels = emptyList())

            val findings = validator.validate(mr)
            assertEquals(2, findings.size)
            assertTrue(findings.all { it.severity == Severity.ERROR })
            assertTrue(findings.any { it.message.contains(SecurityConsciousProfileModule.SECURITY_REVIEW_LABEL) })

            val labelled = mr.copy(labels = listOf(SecurityConsciousProfileModule.SECURITY_REVIEW_LABEL))
            val reviewed = labelled.copy(approvals = listOf(Approval(User(username = "bob", name = "bob"))))
            assertTrue(validator.validate(reviewed).isEmpty())
            assertTrue(validator.validate(mergeRequest(path = "src/Main.kt", labels = emptyList())).isEmpty())
        }

    /** The label alone is self-service, so a protected file also needs a non-author approval. */
    @Test
    fun `label by the author alone does not unblock a protected file`() =
        runTest {
            val config = KarandashJson.Default.decodeFromJsonElement(
                FileChangesConfig.serializer(),
                module.ruleConfigs().getValue("fileChanges"),
            )
            val labelled = mergeRequest(path = "app/.env", labels = listOf(SecurityConsciousProfileModule.SECURITY_REVIEW_LABEL))
            val selfApproved = labelled.copy(approvals = listOf(Approval(User(username = "alice", name = "alice"))))

            assertEquals(1, FileChangesValidator(config).validate(labelled).size)
            assertEquals(1, FileChangesValidator(config).validate(selfApproved).size)
        }

    private fun mergeRequest(
        path: String,
        labels: List<String>,
    ) = MergeRequest(
        id = "1",
        iid = 1,
        title = "feat: rotate keys",
        description = null,
        author = User(username = "alice", name = "alice"),
        sourceBranch = "feature/x",
        targetBranch = "main",
        state = MergeRequestState.OPENED,
        draft = false,
        labels = labels,
        commits = emptyList(),
        changes = listOf(FileChange(path = path, changeType = ChangeType.MODIFIED)),
        approvals = emptyList(),
        reviewers = emptyList(),
    )
}
