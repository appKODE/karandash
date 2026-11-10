package io.karandash.validators

import io.karandash.api.PluginContext
import io.karandash.api.module.PlatformServiceModule
import io.karandash.api.module.RuleModule
import io.karandash.fixing.AutoFixer
import io.karandash.fixing.FixType
import io.karandash.models.Commit
import io.karandash.models.CommitAuthor
import io.karandash.models.DiffRefs
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.models.finding.FixSuggestion
import io.karandash.patterns.CaseType
import io.karandash.testfixtures.TestPluginContext
import io.karandash.testfixtures.createTestMergeRequest
import io.karandash.testfixtures.toJsonElement
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Every pattern rule states only the problem in `message` and puts the fix in `suggestion`, and only a
 * deterministic title correction and the missing description section are ever offered as autofixes.
 */
class FixHintContractTest {
    private class Case(
        val module: RuleModule,
        val ruleId: String,
        val config: JsonElement,
        val mr: MergeRequest,
    )

    private val fixWording = listOf("Expected", "Suggested", "Did you mean", "must start", "must be", "e.g.")

    private fun commit(message: String) =
        Commit(sha = "abc1234", message = message, author = CommitAuthor("user", "user@example.com"), timestamp = "2024-01-01T00:00:00Z")

    private val failingFixtures = listOf(
        Case(
            TitleFormatRuleModule(),
            TitleFormatRuleModule.RULE_ID,
            TitleFormatConfig(pattern = "^(feat|fix): .+$", minLength = 1).toJsonElement(),
            createTestMergeRequest(title = "feat add login"),
        ),
        Case(
            CommitMessagesRuleModule(),
            CommitMessagesRuleModule.RULE_ID,
            CommitMessagesConfig(pattern = "^(feat|fix): .+$").toJsonElement(),
            createTestMergeRequest(commits = listOf(commit("feat add login"))),
        ),
        Case(
            TitleFormatRuleModule(),
            TitleFormatRuleModule.RULE_ID,
            TitleFormatConfig(requireTicket = true, minLength = 1).toJsonElement(),
            createTestMergeRequest(title = "add login"),
        ),
        Case(
            CommitMessagesRuleModule(),
            CommitMessagesRuleModule.RULE_ID,
            CommitMessagesConfig(minLength = 10, maxLength = 12).toJsonElement(),
            createTestMergeRequest(commits = listOf(commit("ok"), commit("feat: add the login screen"))),
        ),
        Case(
            CommitMessagesRuleModule(),
            CommitMessagesRuleModule.RULE_ID,
            CommitMessagesConfig(format = "conventional").toJsonElement(),
            createTestMergeRequest(commits = listOf(commit("add login"))),
        ),
        Case(
            BranchNamingRuleModule(),
            BranchNamingRuleModule.RULE_ID,
            BranchNamingConfig(allowedPatterns = listOf("^feature/.+$", "^bugfix/.+$")).toJsonElement(),
            createTestMergeRequest(sourceBranch = "bugfixlogin"),
        ),
        Case(
            BranchNamingRuleModule(),
            BranchNamingRuleModule.RULE_ID,
            BranchNamingConfig(prefixOneOf = listOf("feat/", "fix/")).toJsonElement(),
            createTestMergeRequest(sourceBranch = "feature/login"),
        ),
        Case(
            BranchNamingRuleModule(),
            BranchNamingRuleModule.RULE_ID,
            BranchNamingConfig(prefixOneOf = listOf("feat/"), case = CaseType.KEBAB).toJsonElement(),
            createTestMergeRequest(sourceBranch = "feat/Add_Login"),
        ),
        Case(
            DescriptionFormatRuleModule(),
            DescriptionFormatRuleModule.RULE_ID,
            DescriptionFormatConfig(listOf(SectionRule("Summary", "^## Summary", required = true))).toJsonElement(),
            createTestMergeRequest(description = "text"),
        ),
        Case(
            JiraTicketRuleModule(),
            JiraTicketRuleModule.RULE_ID,
            JiraTicketConfig(projectKeys = listOf("PROJ")).toJsonElement(),
            createTestMergeRequest(title = "add login", description = null),
        ),
        Case(
            JiraTicketRuleModule(),
            JiraTicketRuleModule.RULE_ID,
            JiraTicketConfig(projectKeys = listOf("PROJ")).toJsonElement(),
            createTestMergeRequest(title = "OPS-7 add login", description = null),
        ),
        Case(
            ContextTagRuleModule(),
            ContextTagRuleModule.RULE_ID,
            ContextTagConfig(allowed = listOf("ios", "core"), checkCommitTitles = false).toJsonElement(),
            createTestMergeRequest(title = "add login"),
        ),
        Case(
            ContextTagRuleModule(),
            ContextTagRuleModule.RULE_ID,
            ContextTagConfig(allowed = listOf("ios", "core"), checkCommitTitles = false).toJsonElement(),
            createTestMergeRequest(title = "[ios][core] add login"),
        ),
    )

    private suspend fun findings(case: Case): List<Finding> {
        assertTrue(case.module.initialize(TestPluginContext()))
        return case.module.validate(case.ruleId, case.mr, case.config)
    }

    @Test
    fun `pattern rule findings keep the fix out of the message and in the suggestion`() =
        runTest {
            for (case in failingFixtures) {
                val findings = findings(case)
                assertTrue(findings.isNotEmpty(), "${case.ruleId} fixture should fail: ${case.mr}")
                for (finding in findings) {
                    assertFalse(finding.suggestion.isNullOrBlank(), "${case.ruleId}: blank suggestion for '${finding.message}'")
                    val leaked = fixWording.filter { finding.message.contains(it) }
                    assertTrue(leaked.isEmpty(), "${case.ruleId}: message carries fix wording $leaked: '${finding.message}'")
                }
            }
        }

    @Test
    fun `only a deterministic title fix and a missing description section are offered for autofix`() =
        runTest {
            val withFixes = setOf(TitleFormatRuleModule.RULE_ID, DescriptionFormatRuleModule.RULE_ID)
            for (case in failingFixtures.filter { it.ruleId !in withFixes }) {
                val fixes = findings(case).flatMap { it.fixSuggestions }
                assertTrue(fixes.isEmpty(), "${case.ruleId} must not offer autofixes: $fixes")
            }
            val description = failingFixtures.first { it.ruleId == DescriptionFormatRuleModule.RULE_ID }
            assertEquals(
                listOf(FixSuggestion.SetDescription("text\n\n## Summary")),
                findings(description).flatMap { it.fixSuggestions },
            )
        }

    @Test
    fun `AutoFixer applies the deterministic SetTitle of title-format`() =
        runTest {
            val case = failingFixtures.first { it.ruleId == TitleFormatRuleModule.RULE_ID }
            val mutator = TitleRecorder()
            val fixer = AutoFixer(mutator, projectId = "group/project", mrId = "1")

            val actions = fixer.analyzeFixable(findings(case))
            val results = fixer.applyFixes(actions)

            assertEquals(listOf(FixType.UPDATE_TITLE), actions.map { it.type })
            assertTrue(results.all { it.success }, "$results")
            assertEquals(listOf("feat: add login"), mutator.titles)
        }

    @Test
    fun `a synthetic example title such as JIRA-0 is never applied`() =
        runTest {
            val case = Case(
                TitleFormatRuleModule(),
                TitleFormatRuleModule.RULE_ID,
                TitleFormatConfig(pattern = "^JIRA-\\d+: .+$", minLength = 1).toJsonElement(),
                createTestMergeRequest(title = "add login"),
            )
            val findings = findings(case)

            assertTrue(findings.isNotEmpty())
            assertTrue(AutoFixer(TitleRecorder(), projectId = "group/project", mrId = "1").analyzeFixable(findings).isEmpty())
        }

    /** Records title updates; every other platform call is unused here. */
    private class TitleRecorder : PlatformServiceModule {
        val titles = mutableListOf<String>()

        override val id: String = "title-recorder"
        override val name: String = "Title recorder"
        override val version: String = "1.0.0"

        override suspend fun initialize(context: PluginContext): Boolean = true

        override suspend fun updateTitle(
            projectId: String,
            mrId: String,
            title: String,
        ) {
            titles += title
        }

        override suspend fun addLabels(
            projectId: String,
            mrId: String,
            labels: List<String>,
        ) = Unit

        override suspend fun removeLabels(
            projectId: String,
            mrId: String,
            labels: List<String>,
        ) = Unit

        override suspend fun setDraft(
            projectId: String,
            mrId: String,
            draft: Boolean,
        ) = Unit

        override suspend fun updateDescription(
            projectId: String,
            mrId: String,
            description: String,
        ) = Unit

        override suspend fun approve(
            projectId: String,
            mrId: String,
        ) = Unit

        override suspend fun unapprove(
            projectId: String,
            mrId: String,
        ) = Unit

        override suspend fun fetchMergeRequest(
            projectId: String,
            mrId: String,
        ): MergeRequest = error("not used")

        override suspend fun fetchMergeRequestByUrl(url: String): MergeRequest = error("not used")

        override suspend fun postComment(
            projectId: String,
            mrId: String,
            body: String,
        ) = ""

        override suspend fun updateComment(
            projectId: String,
            mrId: String,
            commentId: String,
            body: String,
        ) = Unit

        override suspend fun findExistingComment(
            projectId: String,
            mrId: String,
            marker: String,
        ): String? = null

        override suspend fun postInlineComment(
            projectId: String,
            mrId: String,
            body: String,
            filePath: String,
            line: Int,
            diffRefs: DiffRefs,
            startLine: Int?,
        ) = ""
    }
}
