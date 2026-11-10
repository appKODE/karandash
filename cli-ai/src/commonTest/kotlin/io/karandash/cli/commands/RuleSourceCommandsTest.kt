package io.karandash.cli.commands

import com.github.ajalt.clikt.testing.test
import io.karandash.api.exception.KarandashException
import io.karandash.cli.commands.mutate.MutateCommand
import io.karandash.cli.commands.testrule.TestRuleCommand
import io.karandash.cli.commands.verify.VerifyPolicyCommand
import io.karandash.cli.errors.ExitCodes
import io.karandash.config.Config
import io.karandash.config.PolicyConfig
import io.karandash.config.RulesConfig
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import io.karandash.models.support.KarandashJson
import io.karandash.platform.writeText
import io.karandash.testfixtures.TestFiles
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.json.JsonObject
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `mutate`, `verify-policy` and `test-rule` over a [FakeRules] source: no config loading, no platform. */
class RuleSourceCommandsTest {
    private val ruleConfigured = Config(rules = RulesConfig(ruleConfigs = mapOf("title-format" to JsonObject(emptyMap()))))

    private fun finding(rule: String) = Finding(severity = Severity.ERROR, message = "bad", rule = rule)

    private fun mr(id: String) =
        MergeRequest(
            id = id,
            iid = 1,
            title = "MR $id",
            description = null,
            sourceBranch = "feature",
            targetBranch = "main",
            author = User(username = "dev", name = "Dev"),
            state = MergeRequestState.OPENED,
            draft = false,
            labels = emptyList(),
            commits = emptyList(),
            changes = emptyList(),
        )

    private fun snapshot(
        mr: MergeRequest,
        json: String = KarandashJson.Default.encodeToString(MergeRequest.serializer(), mr),
    ): String {
        val dir = TestFiles.createTestTempDirectory("snapshots")
        SystemFileSystem.createDirectories(dir)
        return Path(dir, "${mr.id}.json").also { it.writeText(json) }.toString()
    }

    @Test
    fun `mutate reports caught and escaped mutations`() {
        val rules = FakeRules { listOf(finding("title-format")) }
        val result = MutateCommand(rules).test(listOf("--file", "cfg.yaml"))
        assertEquals(0, result.statusCode, result.output)
        assertContains(result.stdout, "[CAUGHT] bad-title-format")
        assertContains(result.stdout, "[ESCAPED] no-description")
        assertEquals(listOf<String?>("cfg.yaml"), rules.paths)
        assertTrue(rules.filters.single()("anything"))
    }

    @Test
    fun `mutate with a rule loads only that rule and retargets every mutation`() {
        val rules = FakeRules { listOf(finding("custom")) }
        val result = MutateCommand(rules).test(listOf("--rule", "custom"))
        assertContains(result.stdout, "Mutation score: 100%")
        assertFalse("ESCAPED" in result.stdout, result.stdout)
        assertTrue(rules.filters.single()("custom"))
        assertFalse(rules.filters.single()("title-format"))
    }

    @Test
    fun `mutate synthetic mrs above the template count numbers the repeats`() {
        val result = MutateCommand(FakeRules()).test(listOf("--synthetic-mrs", "20"))
        assertContains(result.stdout, "(0/20 caught)")
        assertContains(result.stdout, "bad-title-format#2")
    }

    @Test
    fun `mutate reports a config failure with the config exit code`() {
        val result = MutateCommand(FakeRules(failure = KarandashException("broken config"))).test(emptyList())
        assertEquals(ExitCodes.CONFIG_ERROR, result.statusCode)
        assertContains(result.stderr, "Mutation testing failed: broken config")
    }

    @Test
    fun `verify-policy without policies has nothing to verify`() {
        val rules = FakeRules()
        val result = VerifyPolicyCommand(rules).test(listOf("--config", "p.yaml"))
        assertEquals(0, result.statusCode, result.output)
        assertContains(result.stdout, "No 'policies:' block")
        assertEquals(listOf<String?>("p.yaml"), rules.paths)
        assertFalse(rules.filters.single()("title-format"))
    }

    @Test
    fun `verify-policy exits with validation failed on a contradiction`() {
        val policies = listOf(
            PolicyConfig(condition = "is_draft", modules = listOf("title-format"), severity = "warning"),
            PolicyConfig(condition = "is_draft", modules = listOf("title-format"), severity = "error"),
        )
        val result = VerifyPolicyCommand(FakeRules(Config(policies = policies))).test(emptyList())
        assertEquals(ExitCodes.VALIDATION_FAILED, result.statusCode, result.output)
        assertContains(result.stdout, "Valid: false")
        assertContains(result.stdout, "[ERROR]")
    }

    @Test
    fun `verify-policy passes consistent policies`() {
        val policies = listOf(PolicyConfig(condition = "is_draft", severity = "warning"))
        val result = VerifyPolicyCommand(FakeRules(Config(policies = policies))).test(emptyList())
        assertEquals(0, result.statusCode, result.output)
        assertContains(result.stdout, "Policies checked: 1")
        assertContains(result.stdout, "Valid: true")
    }

    @Test
    fun `verify-policy reports a config failure with the config exit code`() {
        val result = VerifyPolicyCommand(FakeRules(failure = KarandashException("nope"))).test(emptyList())
        assertEquals(ExitCodes.CONFIG_ERROR, result.statusCode)
        assertContains(result.stderr, "Policy verification failed: nope")
    }

    @Test
    fun `test-rule replays snapshots and fetched MRs with the configured rule`() {
        val fetchedUrls = mutableListOf<String>()
        val rules = FakeRules(
            config = ruleConfigured,
            fetched = { url -> mr("fetched").also { fetchedUrls += url } },
            validate = { if (it.id == "fetched") listOf(finding("title-format")) else emptyList() },
        )
        val args = listOf("title-format", "--snapshot", snapshot(mr("s1")), "--mr-url", "https://git/mr/1")
        val result = TestRuleCommand(rules).test(args)
        assertEquals(0, result.statusCode, result.output)
        assertContains(result.stdout, "Rule Sandbox: title-format")
        assertContains(result.stdout, "Replayed: 2 MR(s)")
        assertContains(result.stdout, "Fire rate: 50%")
        assertContains(result.stdout, "Pass: 1 | Fail: 1")
        assertFalse("Recorded history only" in result.stdout, result.stdout)
        assertEquals(listOf("https://git/mr/1"), fetchedUrls)
        assertTrue(rules.filters.single()("title-format"))
        assertFalse(rules.filters.single()("labels"))
    }

    @Test
    fun `test-rule without the rule block in the config fails`() {
        val result = TestRuleCommand(FakeRules()).test(listOf("title-format", "--snapshot", snapshot(mr("s1"))))
        assertEquals(ExitCodes.CONFIG_ERROR, result.statusCode)
        assertContains(result.stderr, "No 'rules.title-format' block")
    }

    @Test
    fun `test-rule with an invalid snapshot fails with the config exit code`() {
        val path = snapshot(mr("bad"), json = "{not json")
        val result = TestRuleCommand(FakeRules(ruleConfigured)).test(listOf("title-format", "--snapshot", path))
        assertEquals(ExitCodes.CONFIG_ERROR, result.statusCode)
        assertContains(result.stderr, "Rule replay failed: invalid snapshot JSON")
    }

    @Test
    fun `test-rule reports a config failure with the config exit code`() {
        val rules = FakeRules(failure = KarandashException("no config"))
        val result = TestRuleCommand(rules).test(listOf("title-format", "--mr-url", "https://git/mr/1"))
        assertEquals(ExitCodes.CONFIG_ERROR, result.statusCode)
        assertContains(result.stderr, "Rule replay failed: no config")
    }

    @Test
    fun `test-rule ignores an invalid ai sandbox block`() {
        val ai = KarandashJson.Default.parseToJsonElement("""{"sandbox":{"historyWindow":"many"}}""")
        val empty = TestFiles.createTestTempDirectory("sandbox").toString()
        val result = TestRuleCommand(FakeRules()) { ai }.test(listOf("title-format", "--stats-dir", empty))
        assertEquals(0, result.statusCode, result.output)
        assertContains(result.stderr, "Ignoring invalid ai.sandbox")
        assertContains(result.stdout, "No validation history found in $empty")
    }
}
