package io.karandash.plugins.ai.core.pipeline

import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import io.karandash.plugins.ai.core.AiFindingMetadataKeys
import io.karandash.plugins.ai.core.AiRuleIds
import io.karandash.plugins.ai.core.SecretRedactor
import io.karandash.plugins.ai.core.isAiFinding
import io.karandash.plugins.ai.core.pipeline.AiGroundingVerifier.Companion.extractCodeSnippets
import io.karandash.plugins.ai.core.pipeline.AiGroundingVerifier.Companion.extractProseSymbols
import io.karandash.plugins.ai.core.pipeline.AiGroundingVerifier.Companion.extractSymbols
import io.karandash.sdk.Logger
import io.karandash.sdk.NoOpLogger

/**
 * The grounding pass of [AiGroundingVerifier.verify], one verdict per AI file finding on a changed file.
 *
 * **Refuted** (dropped, or INFO with a [REFUTED] prefix per [GroundingConfig.failureMode]) only when the claim is
 * contradicted: a backticked name or code snippet absent from the whole MR (diff and new file text, the original checks), an
 * existence-type negation ("`foo` is undefined", "not found", "does not exist") about a name that is a whole token
 * of the MR's added or context lines or new file text, or an [AiFindingMetadataKeys.EVIDENCE] line absent from the
 * finding's file.
 *
 * **Demoted** one severity level for a softer miss: a backticked name that is not a whole token within
 * [GroundingConfig.windowLines] lines of the anchor (or in the file's added and context diff lines), a prose name
 * absent from the MR, or an evidence line in the file but not within [EVIDENCE_SLACK] lines of the anchor.
 * Several misses still lower it one level. `miss: drop` drops these instead, except a BUG/SEC/DATA/LEAK finding.
 *
 * A code suggestion paired with a review comment ([AiMetadataTransformer.pairsWith]) also takes its comment's
 * verdict when that is worse than its own.
 */
internal class LocalGrounding(
    private val config: GroundingConfig,
    mr: MergeRequest,
    private val logger: Logger = NoOpLogger,
) {
    private val changes: Map<String, FileChange> = mr.changes.associateBy { it.path }

    // the original whole-MR evidence (removed lines included): a name absent from all of it refutes
    private val wholeMr = mr.changes.joinToString("\n") { it.diffContent.orEmpty() + "\n" + it.newContent.orEmpty() }

    // what the MR has after the change: added and context lines and the new file text, not removed lines
    private val mrNew = mr.changes.joinToString("\n") { newSide(it.diffContent) + "\n" + it.newContent.orEmpty() }

    /** Grounds [findings]: order is kept, a dropped finding is left out. */
    fun run(findings: List<Finding>): GroundingResult {
        val verdicts = findings.map { assess(it) }
        val final = verdicts.mapIndexed { i, own -> withParent(findings, verdicts, i, own) }
        val kept = findings.zip(final).mapNotNull { (finding, verdict) -> apply(finding, verdict) }
        val refuted = final.count { it.level == Level.REFUTED }
        logger.debug("Grounding: checked ${final.count { it.level != Level.UNCHECKED }} finding(s), refuted $refuted")
        return GroundingResult(
            findings = kept.map { it.first },
            refutedCount = refuted,
            totalAiFindings = findings.count { it.isAiFinding() },
            records = findings.zip(final).map { (finding, verdict) -> record(finding, verdict) },
        )
    }

    private fun assess(finding: Finding): Verdict {
        if (!finding.isAiFinding()) return Verdict.UNCHECKED
        val loc = finding.location as? Location.FileRef ?: return Verdict.UNCHECKED
        val change = changes[loc.path] ?: return Verdict.UNCHECKED
        val refutes = legacyRefutes(finding) + negatedClaims(finding) + evidenceAbsent(finding, change)
        val misses = if (refutes.isNotEmpty()) {
            emptyList()
        } else {
            proseMisses(finding) + windowMisses(finding, loc, change) + evidenceAway(finding, loc, change)
        }
        (refutes + misses).forEach {
            logger.debug("  grounding [${it.substringBefore(':')}] FAILED for [${finding.rule}] @ ${loc.path}:${loc.line} — $it")
        }
        return when {
            refutes.isNotEmpty() -> Verdict(Level.REFUTED, refutes)
            misses.isNotEmpty() -> Verdict(Level.DEMOTED, misses)
            else -> Verdict(Level.GROUNDED, emptyList())
        }
    }

    /** A code suggestion paired with a review comment takes the comment's verdict when it is worse. */
    private fun withParent(
        findings: List<Finding>,
        verdicts: List<Verdict>,
        index: Int,
        own: Verdict,
    ): Verdict {
        val twin = findings[index]
        if (twin.rule != AiRuleIds.CODE_SUGGESTIONS) return own
        val parent = findings.indices.firstOrNull {
            findings[it].rule == AiRuleIds.REVIEW_COMMENTS && AiMetadataTransformer.pairsWith(findings[it], twin)
        } ?: return own
        val inherited = verdicts[parent]
        return if (inherited.level > own.level) Verdict(inherited.level, inherited.reasons.map { "review comment: $it" }) else own
    }

    private fun apply(
        finding: Finding,
        verdict: Verdict,
    ): Pair<Finding, Verdict>? {
        val reason = verdict.reasons.joinToString("; ")
        val after = when (verdict.level) {
            Level.UNCHECKED -> finding
            Level.GROUNDED -> finding.withMetadata(AiFindingMetadataKeys.GROUNDING to GROUNDED)
            Level.DEMOTED -> demote(finding, reason)
            Level.REFUTED -> refute(finding, verdict.reasons)
        }
        return after?.let { it to verdict }
    }

    private fun demote(
        finding: Finding,
        reason: String,
    ): Finding? {
        if (config.miss == GroundingConfig.MISS_DROP && !FindingCap.isProtected(finding)) return null
        return finding.copy(severity = finding.severity.oneLower()).withMetadata(
            AiFindingMetadataKeys.GROUNDING to DEMOTED,
            AiFindingMetadataKeys.DECISION_REASON to "grounding: $reason",
        )
    }

    /** Null drops [finding] (the default); `failureMode: downgrade` keeps it at INFO with a [REFUTED] prefix. */
    private fun refute(
        finding: Finding,
        reasons: List<String>,
    ): Finding? {
        val downgrade = config.failureMode == GroundingConfig.FAILURE_MODE_DOWNGRADE
        // autoDowngradeRefuted=false keeps it as is, with the prefix
        if (!downgrade && (config.failureMode == GroundingConfig.FAILURE_MODE_REMOVE || config.autoDowngradeRefuted)) return null
        val names = reasons.map { it.substringBefore(':') }.distinct().joinToString(", ")
        return finding
            .copy(severity = if (downgrade) Severity.INFO else finding.severity, message = "[REFUTED: $names] ${finding.message}")
            .withMetadata(
                AiFindingMetadataKeys.GROUNDING to REFUTED,
                AiFindingMetadataKeys.DECISION_REASON to "grounding: ${reasons.joinToString("; ")}",
            )
    }

    private fun record(
        finding: Finding,
        verdict: Verdict,
    ): StageRecord {
        val after = apply(finding, verdict)?.first
        val decision = when {
            after == null -> StageRecord.DROP
            after.severity != finding.severity -> StageRecord.DEMOTE
            else -> StageRecord.KEEP
        }
        val reason = if (verdict.level ==
            Level.UNCHECKED
        ) {
            "not checked: no AI finding on a changed file"
        } else {
            verdict.reasons.joinToString("; ")
        }
        return StageRecord.of(finding, after, decision, reason)
    }

    /** The original checks: a backticked name or a code snippet absent from the whole MR refutes. */
    private fun legacyRefutes(finding: Finding): List<String> {
        val refutes = mutableListOf<String>()
        val quoted = if (config.checkSymbolExistence) extractSymbols(finding.message) else emptyList()
        if (quoted.isNotEmpty() && quoted.none { grounded(it, wholeMr) }) {
            refutes += "symbol-existence: ${quoted.joinToString { "`$it`" }} not in the MR"
        }
        if (config.checkCodeSnippets) {
            val snippets = extractCodeSnippets(finding.message) + extractCodeSnippets(finding.explanation.orEmpty())
            if (snippets.isNotEmpty() && snippets.none { grounded(it.trim(), wholeMr) }) {
                refutes += "code-snippet: ${snippets.joinToString { "`$it`" }} not in the MR"
            }
        }
        return refutes
    }

    /** The original soft check: a prose name absent from the MR lowers the finding. */
    private fun proseMisses(finding: Finding): List<String> {
        if (!config.checkSymbolExistence || extractSymbols(finding.message).isNotEmpty()) return emptyList()
        val prose = extractProseSymbols(finding.message)
        if (prose.isEmpty() || prose.any { grounded(it, wholeMr) }) return emptyList()
        return listOf("symbol-existence: prose ${prose.joinToString()} not in the MR")
    }

    /** "`foo` is undefined" while `foo` is a whole token of the changed code: the claim is false. */
    private fun negatedClaims(finding: Finding): List<String> {
        if (!config.negatedClaims) return emptyList()
        val text = finding.message
        return BACKTICKED
            .findAll(text)
            .filter { negatedNear(text, it.range) }
            .map { it.groupValues[1].trim().removeSuffix("()") }
            .filter { it.isNotEmpty() && containsToken(mrNew, it) }
            .map { "negated-claim: `$it` is said not to exist but is in the MR" }
            .toList()
    }

    private fun windowMisses(
        finding: Finding,
        loc: Location.FileRef,
        change: FileChange,
    ): List<String> {
        if (!config.checkSymbolExistence) return emptyList()
        val symbols = extractSymbols(finding.message).filterNot { headOf(it) in STDLIB_NAMES }
        if (symbols.isEmpty()) return emptyList()
        val window = linesAround(change.newContent, loc.line, config.windowLines) + "\n" + newSide(change.diffContent)
        return symbols
            .filterNot { tokenGrounded(it, window) }
            .map { "window: `$it` not within ${config.windowLines} lines of line ${loc.line ?: "-"}" }
    }

    private fun evidenceAbsent(
        finding: Finding,
        change: FileChange,
    ): List<String> {
        val evidence = evidenceOf(finding) ?: return emptyList()
        val file = change.newContent ?: return emptyList()
        return if (quoted(file).contains(evidence)) emptyList() else listOf("evidence: quoted line not in ${change.path}")
    }

    private fun evidenceAway(
        finding: Finding,
        loc: Location.FileRef,
        change: FileChange,
    ): List<String> {
        val evidence = evidenceOf(finding) ?: return emptyList()
        val content = change.newContent ?: return emptyList()
        val anchors = listOfNotNull(loc.line, finding.metadata[AiFindingMetadataKeys.LLM_LINE]?.toIntOrNull())
        // a quote of several lines must fit the window whole, so the window grows by its length
        val slack = EVIDENCE_SLACK + finding.metadata[AiFindingMetadataKeys.EVIDENCE]
            .orEmpty()
            .lines()
            .size
        if (anchors.isEmpty() || anchors.any { quoted(linesAround(content, it, slack)).contains(evidence) }) return emptyList()
        return listOf("evidence: quoted line not within $EVIDENCE_SLACK lines of line ${loc.line}")
    }

    private fun evidenceOf(finding: Finding): String? =
        finding.metadata[AiFindingMetadataKeys.EVIDENCE]
            ?.takeIf { config.evidenceCheck }
            ?.let { normalize(it) }
            ?.takeIf { it.isNotEmpty() }

    private enum class Level { UNCHECKED, GROUNDED, DEMOTED, REFUTED }

    /** Not @Serializable: an internal per-finding verdict, never stored or dumped (its [StageRecord] is). */
    private data class Verdict(
        val level: Level,
        val reasons: List<String>,
    ) {
        companion object {
            val UNCHECKED = Verdict(Level.UNCHECKED, emptyList())
        }
    }

    companion object {
        const val GROUNDED = "grounded"
        const val DEMOTED = "demoted"
        const val REFUTED = "refuted"

        /** An evidence line counts as at the anchor within this many lines of it. */
        const val EVIDENCE_SLACK = 2

        private const val NEAR = 40
        private const val MIN_SYMBOL_LENGTH = 2
        private val BACKTICKED = Regex("`([^`]+)`")
        private val WHITESPACE = Regex("\\s+")
        private val EXISTENCE_NEGATION = Regex(
            "\\b(undefined|not defined|not found|not shown|does not exist|doesn't exist|is not declared)\\b",
            RegexOption.IGNORE_CASE,
        )

        /** Standard-library names: a window miss on these says nothing about the finding. */
        private val STDLIB_NAMES = setOf(
            "String",
            "Int",
            "Long",
            "Double",
            "Float",
            "Boolean",
            "Char",
            "Byte",
            "Short",
            "Unit",
            "Any",
            "Nothing",
            "List",
            "MutableList",
            "Map",
            "MutableMap",
            "Set",
            "MutableSet",
            "Array",
            "Pair",
            "Triple",
            "Sequence",
            "Exception",
            "Throwable",
            "RuntimeException",
            "IllegalStateException",
            "IllegalArgumentException",
            "Object",
            "Integer",
            "System",
            "Math",
            "Optional",
            "Collections",
            "Arrays",
            "println",
            "print",
            "let",
            "apply",
            "also",
            "run",
            "with",
            "takeIf",
            "require",
            "requireNotNull",
            "check",
            "checkNotNull",
            "error",
            "listOf",
            "mapOf",
            "setOf",
            "emptyList",
            "emptyMap",
            "emptySet",
            "lazy",
            "null",
            "true",
            "false",
            "this",
            "it",
            "super",
            "return",
            "val",
            "var",
            "fun",
            "if",
            "else",
            "when",
            "toString",
            "equals",
            "hashCode",
            "size",
            "length",
            "isEmpty",
            "isNotEmpty",
            "map",
            "filter",
            "forEach",
        )

        /** True when [token] occurs in [text] with no identifier character right before or after it. */
        internal fun containsToken(
            text: String,
            token: String,
        ): Boolean {
            if (token.isEmpty()) return false
            var from = text.indexOf(token)
            while (from >= 0) {
                val end = from + token.length
                val clearBefore = from == 0 || !isIdentifierChar(text[from - 1])
                val clearAfter = end >= text.length || !isIdentifierChar(text[end])
                if (clearBefore && clearAfter) return true
                from = text.indexOf(token, from + 1)
            }
            return false
        }

        /** Mirrors the original grounded(): the name, or its leading identifier, as a whole token. */
        private fun tokenGrounded(
            symbol: String,
            text: String,
        ): Boolean {
            val name = symbol.removeSuffix("()")
            val head = headOf(name)
            return containsToken(text, name) || (head.length >= MIN_SYMBOL_LENGTH && containsToken(text, head))
        }

        private fun grounded(
            snippet: String,
            text: String,
        ): Boolean {
            val head = headOf(snippet)
            return text.contains(snippet) || (head.length >= MIN_SYMBOL_LENGTH && text.contains(head))
        }

        private fun headOf(symbol: String): String = symbol.takeWhile { isIdentifierChar(it) }

        private fun isIdentifierChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_'

        /** An existence-type negation within [NEAR] characters after (or before) the backticked name, no other name between. */
        private fun negatedNear(
            text: String,
            range: IntRange,
        ): Boolean {
            val after = text.substring(range.last + 1, minOf(text.length, range.last + 1 + NEAR)).substringBefore('`').substringBefore(". ")
            val before = text.substring(maxOf(0, range.first - NEAR), range.first).substringAfterLast('`').substringAfterLast(". ")
            return isExistenceNegation(after) || isExistenceNegation(before)
        }

        // "can be undefined" is about a value (JS `undefined`), not about the name existing
        private fun isExistenceNegation(text: String): Boolean =
            EXISTENCE_NEGATION.findAll(text).any { !text.substring(0, it.range.first).trimEnd().endsWith(" be") }

        private fun linesAround(
            content: String?,
            line: Int?,
            slack: Int,
        ): String {
            val text = content ?: return ""
            if (line == null) return text
            val lines = text.lines()
            val from = (line - 1 - slack).coerceIn(0, lines.size)
            val to = (line + slack).coerceIn(from, lines.size)
            return lines.subList(from, to).joinToString("\n")
        }

        private fun newSide(diff: String?): String =
            diff
                .orEmpty()
                .lines()
                .filterNot { it.startsWith("-") }
                .joinToString("\n")

        // The model saw the redacted diff, so it quotes a secret as [REDACTED]: compare against the file redacted the same way.
        // limit: default patterns only, a custom ai.redact pattern still misses; pass the run's SecretRedactor if one bites.
        private fun quoted(text: String): String = normalize(SecretRedactor.redactText(text))

        private fun normalize(text: String): String =
            text
                .lines()
                .joinToString(
                    " "
                ) { it.trim().removePrefix("+") }
                .trim()
                .split(WHITESPACE)
                .filter { it.isNotEmpty() }
                .joinToString(" ")

        private fun Finding.withMetadata(vararg entries: Pair<String, String>): Finding = copy(metadata = metadata + entries)
    }
}

/** One severity step down, INFO at the bottom. */
internal fun Severity.oneLower(): Severity = Severity.entries[minOf(ordinal + 1, Severity.INFO.ordinal)]
