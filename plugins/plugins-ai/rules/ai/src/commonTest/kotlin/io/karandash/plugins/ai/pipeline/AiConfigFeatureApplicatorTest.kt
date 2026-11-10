@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.plugins.ai.pipeline

import io.karandash.api.module.ExtensionStateStore
import io.karandash.duration.KarandashDuration
import io.karandash.models.Approval
import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.ReviewState
import io.karandash.models.User
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import io.karandash.plugins.ai.AiPipelineExtension
import io.karandash.plugins.ai.core.AiStatePersistence
import io.karandash.plugins.ai.core.config.EnvironmentTrackResolver
import io.karandash.sdk.Logger
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

class AiConfigFeatureApplicatorTest {
    private val infos = mutableListOf<String>()
    private val warnings = mutableListOf<String>()
    private val logger = object : Logger {
        override fun debug(message: String) = Unit

        override fun info(message: String) {
            infos += message
        }

        override fun warn(message: String) {
            warnings += message
        }

        override fun error(message: String) = Unit
    }

    private fun mr(
        description: String? = null,
        labels: List<String> = emptyList(),
        draft: Boolean = false,
    ) = MergeRequest(
        id = "1",
        iid = 1,
        title = "t",
        description = description,
        sourceBranch = "f",
        targetBranch = "main",
        author = User("1", "dev", "Dev"),
        state = MergeRequestState.OPENED,
        draft = draft,
        labels = labels,
        commits = emptyList(),
        changes = listOf(FileChange("src/Foo.kt", ChangeType.MODIFIED), FileChange("docs/a.md", ChangeType.MODIFIED)),
    )

    private fun finding(
        path: String = "src/Foo.kt",
        severity: Severity = Severity.ERROR,
        rule: String = "r1",
    ) = Finding(severity = severity, message = "m", rule = rule, location = Location.FileRef(path, 1))

    private fun parsedAi(json: String) = checkNotNull(AiPipelineExtension.resolveAiConfig(Json.parseToJsonElement(json)))

    private fun apply(
        findings: List<Finding>,
        ai: String,
        mr: MergeRequest = mr(),
    ) = AiConfigFeatureApplicator(logger).apply(findings, mr, parsedAi("""{"ai": $ai}"""))

    private val macroCfg = """{
        "macroDefinitions": {"relax": {"rules": {"r1": "info", "r2": {"enabled": false}, "r3": {"severity": "skip"}}}},
        "macroReferences": [{"whenFilesMatch": ["src/**"], "useMacro": "relax"}]
    }"""

    @Test
    fun `macro severity override changes severity only for matching files`() {
        val result = apply(listOf(finding(), finding(path = "docs/a.md")), macroCfg)
        assertEquals(listOf(Severity.INFO, Severity.ERROR), result.map { it.severity })
    }

    @Test
    fun `an undefined macro in macroReferences warns once`() {
        val ai = """{
            "macroDefinitions": {"relax": {"rules": {"r1": "info"}}},
            "macroReferences": [{"whenFilesMatch": ["src/**"], "useMacro": "missing"}, {"whenFilesMatch": ["docs/**"], "useMacro": "missing"}]
        }"""

        val result = apply(listOf(finding()), ai)

        assertEquals(Severity.ERROR, result.single().severity)
        assertEquals(1, warnings.count { "'missing'" in it }, warnings.toString())
    }

    @Test
    fun `macro enabled false and skip drop the finding`() {
        val result = apply(listOf(finding(rule = "r2"), finding(rule = "r3"), finding(rule = "other")), macroCfg)
        assertEquals(listOf("other"), result.map { it.rule })
    }

    @Test
    fun `team rule override changes severity or drops by ownership`() {
        val ai = """{"teams": [{"name": "core", "owns": ["src/**"],
            "ruleOverrides": {"r1": "warning", "r2": {"enabled": false}}}]}"""
        val result = apply(listOf(finding(), finding(rule = "r2"), finding(path = "docs/a.md")), ai)
        assertEquals(listOf(Severity.WARNING, Severity.ERROR), result.map { it.severity })
    }

    /** `TeamNamespaceResolver.affectedTeams` names the teams owning the MR in the team-scoping log. */
    @Test
    fun `team scoping reports the affected teams only`() {
        val ai = """{"teams": [{"name": "core", "owns": ["src/**"]}, {"name": "web", "owns": ["web/**"]}]}"""
        apply(listOf(finding()), ai)
        assertEquals(listOf("Team scoping: affected teams: core"), infos.filter { it.startsWith("Team scoping") })
    }

    @Test
    fun `active grace period downgrades severity one step`() {
        val ai = """{"gracePeriods": [{"name": "hotfix", "condition": "labels.contains('hotfix')"}]}"""
        val findings = listOf(finding(severity = Severity.ERROR), finding(severity = Severity.WARNING))
        assertEquals(
            listOf(Severity.WARNING, Severity.INFO),
            apply(findings, ai, mr(labels = listOf("hotfix"))).map { it.severity },
        )
        assertEquals(listOf(Severity.ERROR, Severity.WARNING), apply(findings, ai, mr()).map { it.severity })
    }

    @Test
    fun `grace period audit false logs the active grace period at debug only`() {
        val ai = { audit: Boolean -> """{"gracePeriods": [{"name": "hotfix", "condition": "draft", "audit": $audit}]}""" }
        apply(listOf(finding()), ai(true), mr(draft = true))
        assertEquals(listOf("Grace period active: hotfix"), infos.filter { "Grace period" in it })
        infos.clear()
        val result = apply(listOf(finding()), ai(false), mr(draft = true))
        assertEquals(emptyList(), infos.filter { "Grace period" in it })
        assertEquals(Severity.WARNING, result.single().severity)
    }

    @Test
    fun `seniority tier adjustments set the severity of the named rule only`() {
        val ai = """{"seniorityTiers": [{"level": "junior", "matchAuthors": ["1"], "adjustments": {"r1": "info"}}]}"""
        val result = apply(listOf(finding(), finding(rule = "r2")), ai)
        assertEquals(listOf(Severity.INFO, Severity.WARNING), result.map { it.severity })
    }

    @Test
    fun `grace period override rule skip drops the rule`() {
        val ai = """{"gracePeriods": [{"name": "h", "condition": "draft", "overrideRules": {"r1": "skip"}}]}"""
        val result = apply(listOf(finding(), finding(rule = "r2")), ai, mr(draft = true))
        assertEquals(listOf("r2"), result.map { it.rule })
    }

    @Test
    fun `inline override matches rule id and path glob`() {
        val description = """<!-- karandash-override rule: r1 path: src/** reason: "generated" -->"""
        val result = apply(
            listOf(finding(), finding(path = "docs/a.md"), finding(rule = "r1-other")),
            "{}",
            mr(description = description),
        )
        assertEquals(listOf(Severity.INFO, Severity.ERROR, Severity.ERROR), result.map { it.severity })
        assertTrue(result[0].message.startsWith("[OVERRIDDEN: generated]"), result[0].message)
    }

    @Test
    fun `macros and applyMacros apply to every finding including location-less ones`() {
        val ai = """{"macros": {"strict": [{"rule": "r1", "enforceSeverity": "warning"}, {"rule": "r2", "enabled": false}]},
            "applyMacros": ["strict"]}"""
        val noLocation = Finding(severity = Severity.ERROR, message = "m", rule = "r1")
        val result = apply(listOf(finding(), noLocation, finding(rule = "r2"), finding(rule = "r9")), ai)
        assertEquals(
            listOf("r1" to Severity.WARNING, "r1" to Severity.WARNING, "r9" to Severity.ERROR),
            result.map { it.rule to it.severity }
        )
    }

    @Test
    fun `environmentTracks rules apply on the matching target branch only`() {
        val ai = """{"environmentTracks": [{"targetBranch": "main|master",
            "rules": {"size-limits": {"enabled": false}, "r1": {"enforceSeverity": "info"}}}]}"""
        val findings = listOf(finding(), finding(rule = "size-limits"))
        assertEquals(listOf("r1" to Severity.INFO), apply(findings, ai).map { it.rule to it.severity })
        val onRelease = apply(findings, ai, mr().copy(targetBranch = "maintenance"))
        assertEquals(listOf(Severity.ERROR, Severity.ERROR), onRelease.map { it.severity })
    }

    @Test
    fun `camelCased rule keys still match kebab-case rule ids`() {
        val ai = """{"teams": [{"name": "core", "owns": ["src/**"], "rules": {"sizeLimits": "info"}}]}"""
        assertEquals(Severity.INFO, apply(listOf(finding(rule = "size-limits")), ai).single().severity)
    }

    private class MemoryStore : ExtensionStateStore {
        val entries = mutableMapOf<String, ByteArray>()
        override val ttl: KarandashDuration = KarandashDuration.ofSeconds(60)

        override fun get(key: String): ByteArray? = entries[key]

        override fun put(
            key: String,
            content: ByteArray,
        ) {
            entries[key] = content
        }

        override fun invalidate(key: String) {
            entries.remove(key)
        }

        override fun keys(): List<String> = entries.keys.toList()
    }

    /** `ai.enforcementSchedule.*.blockSeverities` picks what stays blocking in partial mode. */
    @Test
    fun `partial enforcement keeps only blockSeverities blocking`() {
        val findings = listOf(finding(severity = Severity.ERROR), finding(severity = Severity.WARNING))
        val custom = apply(
            findings,
            """{"enforcementSchedule": [{"from": "2000-01-01", "mode": "partial", "blockSeverities": ["warning"]}]}""",
        )
        assertEquals(listOf(Severity.WARNING, Severity.WARNING), custom.map { it.severity })
        val unset = apply(findings, """{"enforcementSchedule": [{"from": "2000-01-01", "mode": "partial"}]}""")
        assertEquals(listOf(Severity.ERROR, Severity.WARNING), unset.map { it.severity })
    }

    /** `ai.inlineOverrides.allowed` limits the rules and, with requireReason, needs a reason. */
    @Test
    fun `the enforcement phase is picked by the injected clock`() {
        val schedule = """{"enforcementSchedule": [{"from": "2026-01-01", "mode": "warn"}, {"from": "2026-02-01", "mode": "enforce"}]}"""

        fun severityAt(date: String) =
            AiConfigFeatureApplicator(logger, now = { Instant.parse("${date}T12:00:00Z") })
                .apply(listOf(finding()), mr(), parsedAi("""{"ai": $schedule}"""))
                .single()
                .severity

        assertEquals(Severity.WARNING, severityAt("2026-01-15"))
        assertEquals(Severity.ERROR, severityAt("2026-02-15"))
    }

    @Test
    fun `inlineOverrides allowed rejects unlisted rules and blank reasons`() {
        val description = """<!-- karandash-override rule: r1 reason: "" -->
            <!-- karandash-override rule: r2 reason: "generated" -->
            <!-- karandash-override rule: r3 reason: "generated" -->"""
        val ai = """{"inlineOverrides": {"allowed": [{"rule": "r1", "requireReason": true}, {"rule": "r2"}]}}"""
        val findings = listOf(finding(), finding(rule = "r2"), finding(rule = "r3"))
        val result = apply(findings, ai, mr(description = description))
        assertEquals(listOf(Severity.ERROR, Severity.INFO, Severity.ERROR), result.map { it.severity })
        assertEquals(
            listOf(
                "Inline override of 'r1' not applied: a reason is required",
                "Inline override of 'r3' not applied: rule not in allowed overrides",
            ),
            warnings,
        )
        val unset = apply(findings, "{}", mr(description = description))
        assertEquals(listOf(Severity.INFO, Severity.INFO, Severity.INFO), unset.map { it.severity })
    }

    /** `ai.gracePeriods.*.maxUsesPerDay` caps the MRs per day and `maxDuration` ends an MR window. */
    @Test
    fun `grace period maxUsesPerDay and maxDuration are enforced from AI state`() {
        val ai = """{"gracePeriods": [{"name": "hotfix", "condition": "draft", "maxUsesPerDay": 1, "maxDuration": "4h"}]}"""
        val persistence = AiStatePersistence(MemoryStore())
        val start = Instant.parse("2026-10-26T08:00:00Z")
        val first = mr(draft = true)
        val second = first.copy(id = "2", iid = 2)
        val runs = listOf(first to start, second to start + 1.hours, first to start + 2.hours, first to start + 5.hours)
        val severities = runs.map { (mr, at) ->
            AiConfigFeatureApplicator(logger, persistence = persistence, now = { at })
                .apply(listOf(finding()), mr, parsedAi("""{"ai": $ai}"""))
                .single()
                .severity
        }
        assertEquals(listOf(Severity.WARNING, Severity.ERROR, Severity.WARNING, Severity.ERROR), severities)
        assertEquals(
            listOf(
                "Grace period 'hotfix' not applied: used by 1 MRs today, maxUsesPerDay 1",
                "Grace period 'hotfix' not applied: expired at 2026-10-26T12:00:00Z",
            ),
            warnings,
        )
    }

    /** Unset grace limits keep the grace period open for every MR, as before. */
    @Test
    fun `grace period without limits applies to every MR`() {
        val config = parsedAi("""{"ai": {"gracePeriods": [{"name": "hotfix", "condition": "draft"}]}}""")
        val applicator = AiConfigFeatureApplicator(logger, persistence = AiStatePersistence(MemoryStore()))
        val severities = (1..5).map {
            applicator
                .apply(
                    listOf(finding()),
                    mr(draft = true).copy(id = "$it", iid = it),
                    config
                ).single()
                .severity
        }
        assertEquals(List(5) { Severity.WARNING }, severities)
    }

    /** Grace limits that cannot be enforced without AI state say so once at warn level. */
    @Test
    fun `grace limits without AI state warn once`() {
        val limited = parsedAi(
            """{"ai": {"gracePeriods": [{"name": "hotfix", "condition": "draft", "maxUsesPerDay": 1}]}}""",
        )
        val applicator = AiConfigFeatureApplicator(logger)
        repeat(2) { applicator.apply(listOf(finding()), mr(draft = true), limited) }
        apply(listOf(finding()), """{"gracePeriods": [{"name": "open", "condition": "draft"}]}""", mr(draft = true))
        assertEquals(
            listOf("Grace period 'hotfix': no AI state persistence, maxDuration and maxUsesPerDay cannot be enforced"),
            warnings,
        )
    }

    /** `ai.tracks.*.minApprovals` raises an error when the MR has fewer approvals. */
    @Test
    fun `track minApprovals reports a missing approval`() {
        val ai = """{"tracks": [{"name": "prod", "targetBranchPattern": "main", "minApprovals": 2}]}"""
        val approved = mr().copy(approvals = listOf(Approval(User("2", "rev", "Rev"), state = ReviewState.APPROVED)))
        val result = apply(emptyList(), ai, approved)
        assertEquals(listOf(EnvironmentTrackResolver.APPROVALS_RULE_ID), result.map { it.rule })
        assertEquals("Environment track 'prod' requires 2 approval(s) for main, the MR has 1", result.single().message)
        assertEquals(emptyList(), apply(emptyList(), """{"tracks": [{"name": "prod", "targetBranchPattern": "main"}]}""", approved))
    }

    @Test
    fun `inlineOverrides true shorthand reads description tags`() {
        val description = "<!-- @karandash exempt r1: generated code -->"
        val result = apply(listOf(finding()), """{"inlineOverrides": true}""", mr(description = description))
        assertEquals(Severity.INFO, result.single().severity)
    }

    @Test
    fun `gracePeriod block with enabled true opens the window and enabled false closes it`() {
        val mr = mr(description = "<!-- @karandash grace-period-start: Emergency hotfix -->")
        val severity = { ai: String -> apply(listOf(finding()), ai, mr).single().severity }

        assertEquals(Severity.ERROR, severity("""{}"""))
        assertEquals(Severity.INFO, severity("""{"gracePeriod": {"enabled": true}}"""))
        assertEquals(Severity.ERROR, severity("""{"gracePeriod": {"enabled": false}}"""))
    }

    @Test
    fun `gracePeriod approvalRequired keeps the window shut until enough approvals`() {
        val open = mr(description = "<!-- @karandash grace-period-start: Emergency hotfix -->")
        val approved = open.copy(approvals = listOf(Approval(User("2", "rev", "Reviewer"))))
        val ai = """{"gracePeriod": {"approvalRequired": 1}}"""

        assertEquals(Severity.ERROR, apply(listOf(finding()), ai, open).single().severity)
        assertEquals(Severity.INFO, apply(listOf(finding()), ai, approved).single().severity)
    }
}
