package io.karandash.plugins.ai.core.pipeline

import io.karandash.api.exception.ConfigParseException
import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import io.karandash.plugins.ai.core.AiFindingMetadataKeys
import io.karandash.plugins.ai.core.AiRuleIds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalGroundingTest {
    private val path = "src/Foo.kt"

    // line 1 holds `farAway`, line 100 holds `computeHash`, lines between are filler
    private val content = (listOf("val farAway = 1") + List(98) { "// filler $it" } + listOf("fun computeHash(x: Int) = x.hashCode()"))
        .joinToString("\n")

    private fun mr(
        diff: String = "+fun computeHash(x: Int) = x.hashCode()",
        newContent: String = content,
    ) = MergeRequest(
        id = "1",
        iid = 1,
        title = "Test MR",
        description = null,
        sourceBranch = "feature",
        targetBranch = "main",
        author = User(username = "dev", name = "Developer"),
        state = MergeRequestState.OPENED,
        draft = false,
        labels = emptyList(),
        commits = emptyList(),
        changes = listOf(FileChange(path = path, changeType = ChangeType.MODIFIED, diffContent = diff, newContent = newContent)),
    )

    private fun ai(
        message: String,
        line: Int = 100,
        severity: Severity = Severity.WARNING,
        rule: String = AiRuleIds.REVIEW_COMMENTS,
        category: String = "BUG",
        evidence: String? = null,
    ) = Finding(
        severity = severity,
        message = message,
        rule = rule,
        location = Location.FileRef(path, line),
        metadata = mapOf(AiFindingMetadataKeys.AI_MODEL to "m", AiFindingMetadataKeys.CATEGORY to category) +
            listOfNotNull(evidence?.let { AiFindingMetadataKeys.EVIDENCE to it }),
    )

    private fun ground(
        findings: List<Finding>,
        config: GroundingConfig = GroundingConfig(),
        mr: MergeRequest = mr(),
    ) = AiGroundingVerifier(config).verify(findings, mr)

    @Test
    fun `whole-token match tells has from hash`() {
        assertTrue(LocalGrounding.containsToken("val hash = 1", "hash"))
        assertFalse(LocalGrounding.containsToken("val hash = 1", "has"))
        assertTrue(LocalGrounding.containsToken("x.has(y)", "has"))
    }

    @Test
    fun `a symbol within the window keeps the finding grounded`() {
        val result = ground(listOf(ai("`computeHash` overflows on large input")))

        assertEquals(Severity.WARNING, result.findings.single().severity)
        assertEquals("grounded", result.findings.single().metadata[AiFindingMetadataKeys.GROUNDING])
        assertEquals(StageRecord.KEEP, result.records.single().decision)
    }

    @Test
    fun `a symbol in the file but outside the window demotes one level`() {
        val result = ground(listOf(ai("`farAway` is read before it is set")))

        val finding = result.findings.single()
        assertEquals(Severity.INFO, finding.severity)
        assertEquals("demoted", finding.metadata[AiFindingMetadataKeys.GROUNDING])
        val record = result.records.single()
        assertEquals(StageRecord.DEMOTE, record.decision)
        assertEquals("WARNING", record.severityBefore)
        assertEquals("INFO", record.severityAfter)
        assertTrue(record.reason.contains("not within 40 lines of line 100"))
    }

    @Test
    fun `a wider window keeps the far symbol`() {
        val result = ground(listOf(ai("`farAway` is read before it is set")), GroundingConfig(windowLines = 120))

        assertEquals(Severity.WARNING, result.findings.single().severity)
    }

    @Test
    fun `miss drop drops a demoted nit but never a BUG`() {
        val config = GroundingConfig(miss = GroundingConfig.MISS_DROP)
        val result = ground(listOf(ai("`farAway` is unused", category = "NIT"), ai("`farAway` is read early")), config)

        assertEquals(listOf("BUG"), result.findings.map { it.metadata[AiFindingMetadataKeys.CATEGORY] })
        assertEquals(listOf(StageRecord.DROP, StageRecord.DEMOTE), result.records.map { it.decision })
    }

    @Test
    fun `an existence negation about a symbol in the MR is refuted`() {
        val result = ground(listOf(ai("`computeHash` is undefined in this scope", severity = Severity.ERROR)))

        assertTrue(result.findings.isEmpty())
        assertEquals(1, result.refutedCount)
        assertEquals(StageRecord.DROP, result.records.single().decision)
        assertTrue(
            result.records
                .single()
                .reason
                .startsWith("negated-claim")
        )
    }

    @Test
    fun `a removed claim about a symbol still in the MR is not refuted`() {
        val result = ground(listOf(ai("`computeHash` was removed so callers lost its guard", severity = Severity.ERROR)))

        assertEquals(0, result.refutedCount)
        assertEquals(Severity.ERROR, result.findings.single().severity)
    }

    @Test
    fun `a symbol only on removed lines does not ground an existence negation`() {
        val mr = mr(diff = "-fun oldGuard() = true\n+fun computeHash(x: Int) = x.hashCode()")
        val result = ground(listOf(ai("`oldGuard` is not defined anymore", severity = Severity.ERROR)), mr = mr)

        assertEquals(0, result.refutedCount)
    }

    @Test
    fun `evidence away from the anchor demotes and evidence absent from the file refutes`() {
        val away = ai("`computeHash` overflows", evidence = "val farAway = 1")
        val absent = ai("`computeHash` overflows twice", evidence = "val nowhere = 2")

        val result = ground(listOf(away, absent))

        assertEquals(listOf(Severity.INFO), result.findings.map { it.severity })
        assertEquals(listOf(StageRecord.DEMOTE, StageRecord.DROP), result.records.map { it.decision })
        assertTrue(result.records[1].reason.startsWith("evidence"))
    }

    @Test
    fun `evidence quoting a redacted secret matches the file`() {
        val secret = "private const val WEATHER_API_KEY = \"sk-abcdef0123456789abcdef0123456789\""
        val mr = mr(diff = "+$secret", newContent = secret)
        val leak = ai("Hardcoded API key", line = 1, category = "SEC", evidence = "private const val WEATHER_API_KEY = [REDACTED]")

        val result = ground(listOf(leak), mr = mr)

        assertEquals(StageRecord.KEEP, result.records.single().decision)
    }

    @Test
    fun `evidence of several lines starting at the anchor is at the anchor`() {
        val body = List(10) { "val line$it = $it" }
        val mr = mr(diff = body.joinToString("\n") { "+$it" }, newContent = body.joinToString("\n"))
        val finding = ai("`line2` is wrong", line = 3, evidence = body.subList(2, 8).joinToString("\n"))

        val result = ground(listOf(finding), mr = mr)

        assertEquals(StageRecord.KEEP, result.records.single().decision)
    }

    @Test
    fun `negatedClaims false keeps an existence negation about a symbol in the MR`() {
        val claim = ai("`computeHash` is undefined in this scope", severity = Severity.ERROR)
        val result = ground(listOf(claim), GroundingConfig(negatedClaims = false))

        assertEquals(0, result.refutedCount)
        assertEquals(Severity.ERROR, result.findings.single().severity)
    }

    @Test
    fun `evidenceCheck false ignores the quoted evidence line`() {
        val away = ai("`computeHash` overflows", evidence = "val farAway = 1")
        val absent = ai("`computeHash` overflows twice", evidence = "val nowhere = 2")

        val result = ground(listOf(away, absent), GroundingConfig(evidenceCheck = false))

        assertEquals(listOf(Severity.WARNING, Severity.WARNING), result.findings.map { it.severity })
    }

    @Test
    fun `the code-suggestions twin is dropped with its refuted comment`() {
        val comment = ai("`computeHash` does not exist", severity = Severity.ERROR)
        val twin = ai("Add the function", rule = AiRuleIds.CODE_SUGGESTIONS, severity = Severity.INFO)

        val result = ground(listOf(comment, twin))

        assertTrue(result.findings.isEmpty())
        assertEquals(listOf(StageRecord.DROP, StageRecord.DROP), result.records.map { it.decision })
    }

    @Test
    fun `a bad grounding value is a config error naming the key`() {
        val miss = assertFailsWith<ConfigParseException> { GroundingConfig(miss = "hide").validated() }
        assertTrue(miss.message.orEmpty().contains("ai.grounding.miss"))
        val window = assertFailsWith<ConfigParseException> { GroundingConfig(windowLines = 0).validated() }
        assertTrue(window.message.orEmpty().contains("ai.grounding.windowLines"))
    }
}
