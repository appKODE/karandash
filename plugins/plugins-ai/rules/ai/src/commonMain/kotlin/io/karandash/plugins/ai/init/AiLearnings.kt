package io.karandash.plugins.ai.init

import io.karandash.api.exception.ConfigParseException
import io.karandash.api.module.ConfigFormatModule
import io.karandash.logger.Logger
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import io.karandash.models.support.KarandashJson
import io.karandash.models.support.WellKnownMetadataKeys
import io.karandash.plugins.ai.core.AiFindingMetadataKeys
import io.karandash.plugins.ai.core.AiPluginConfig
import io.karandash.plugins.ai.core.Learning
import io.karandash.plugins.ai.core.LearningAction
import io.karandash.plugins.ai.core.ReviewInstruction
import io.karandash.plugins.ai.core.isAiFinding
import io.karandash.utils.GlobMatcher
import kotlinx.atomicfu.atomic
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/**
 * The `ai.learnings.file` written by `calibrate --learn` (`learnings: [{rule, path?, action, evidence}]`), as read
 * from the MR base: [parse] it, send it as one T0 review instruction ([instructions]) and [apply] it to the findings.
 */
internal object AiLearnings {
    /** Most entries read; the rest are dropped, so the never-cut T0 part stays small. */
    const val MAX_ENTRIES: Int = 100

    /** Longest rule or path text sent to the model. */
    private const val MAX_TEXT = 200
    private const val NOT_A_LIST = "Learnings file is not an object with a `learnings:` list, its learnings are ignored"

    /** Entries of [text] (the file at the MR base; null when absent there, which is the usual case, so not warned). */
    suspend fun parse(
        text: String?,
        parser: ConfigFormatModule?,
        logger: Logger,
    ): List<Learning> {
        if (text == null || parser == null) return emptyList()
        val root =
            try {
                parser.parseToJsonElement(text)
            } catch (e: IllegalArgumentException) {
                logger.warn("Learnings file is not valid YAML, its learnings are ignored: ${e.message}")
                return emptyList()
            } catch (e: ConfigParseException) {
                logger.warn("Learnings file is not valid YAML, its learnings are ignored: ${e.message}")
                return emptyList()
            }
        val entries =
            when (val node = (root as? JsonObject)?.get("learnings")) {
                is JsonArray -> {
                    node
                }

                null, JsonNull -> {
                    return emptyList<Learning>().also {
                        if (root !is JsonObject && text.isNotBlank()) logger.warn(NOT_A_LIST)
                    }
                }

                else -> {
                    return emptyList<Learning>().also { logger.warn(NOT_A_LIST) }
                }
            }
        if (entries.size > MAX_ENTRIES) logger.warn("Learnings file has ${entries.size} entries; those past $MAX_ENTRIES are ignored")
        return entries.take(MAX_ENTRIES).mapNotNull { decode(it, logger) }
    }

    private fun decode(
        entry: JsonElement,
        logger: Logger,
    ): Learning? =
        try {
            KarandashJson.Default.decodeFromJsonElement(Learning.serializer(), entry).let { normalized(it, logger) }
        } catch (e: SerializationException) {
            logger.warn("Learnings file: entry $entry needs rule, action (suppress|downgrade) and evidence; ignored (${e.message})")
            null
        } catch (e: IllegalArgumentException) {
            logger.warn("Learnings file: entry $entry needs rule, action (suppress|downgrade) and evidence; ignored (${e.message})")
            null
        }

    /** [learning] with its path made relative to the repo root (leading `./` or `/` dropped); null, warned, when it climbs with `..`. */
    private fun normalized(
        learning: Learning,
        logger: Logger,
    ): Learning? {
        val path = learning.path ?: return learning
        if (".." in path.split('/')) {
            logger.warn("Learnings file: path '$path' of rule '${learning.rule}' contains '..'; entry ignored")
            return null
        }
        return learning.copy(path = path.trimStart('/').removePrefix("./").trimStart('/'))
    }

    /** [text] on one line for the prompt: no backticks, newlines or control characters, at most [MAX_TEXT] chars. */
    private fun sanitized(text: String): String = text.filter { it != '`' && !it.isISOControl() }.take(MAX_TEXT)

    /** [learnings] as one review instruction for every file (T0, never cut); none when there are no learnings. */
    fun instructions(learnings: List<Learning>): List<ReviewInstruction> {
        if (learnings.isEmpty()) return emptyList()
        val lines = learnings.joinToString("\n") { l ->
            val scope = l.path?.let { " under `${sanitized(it)}`" }.orEmpty()
            val total = l.evidence.rejected + l.evidence.accepted
            val advice = if (l.action == LearningAction.SUPPRESS) "do not report it" else "report only clear, high-impact cases"
            "- `${sanitized(l.rule)}`$scope: reviewers rejected ${l.evidence.rejected} of $total; $advice"
        }
        return listOf(ReviewInstruction(INSTRUCTION_NAME, listOf("**"), "$INTRO\n$lines"))
    }

    /**
     * [findings] with the most specific matching learning applied (the longest path glob; a rule-only entry is the
     * broadest), for AI findings only: suppress drops the finding, downgrade lowers it one
     * severity level and records the learning in [AiFindingMetadataKeys.LEARNING] and the `why` footer.
     */
    fun apply(
        findings: List<Finding>,
        learnings: List<Learning>,
    ): List<Finding> {
        if (learnings.isEmpty()) return findings
        return findings.mapNotNull { finding ->
            if (!finding.isAiFinding()) return@mapNotNull finding
            val learning = learnings.filter { it.matches(finding) }.maxByOrNull { it.path?.length ?: -1 } ?: return@mapNotNull finding
            if (learning.action == LearningAction.SUPPRESS) null else downgrade(finding, learning.path ?: learning.rule)
        }
    }

    private fun Learning.matches(finding: Finding): Boolean {
        if (rule != finding.rule) return false
        val glob = path ?: return true
        val file = (finding.location as? Location.FileRef)?.path ?: return false
        return GlobMatcher.matches(file, glob)
    }

    private fun downgrade(
        finding: Finding,
        cite: String,
    ): Finding {
        val why = finding.metadata[WellKnownMetadataKeys.WHY]?.let { WellKnownMetadataKeys.WHY to "$it; learning: $cite" }
        return finding.copy(
            severity = Severity.entries.getOrElse(finding.severity.ordinal + 1) { finding.severity },
            metadata = finding.metadata + (AiFindingMetadataKeys.LEARNING to cite) + listOfNotNull(why),
        )
    }

    private const val INSTRUCTION_NAME = "learnings from review feedback"
    private const val INTRO = "Reviewers of this project kept rejecting these finding types (karandash calibrate --learn):"
}

/**
 * The review files the AI pipeline extension reads from the MR base: the Duo instructions (`review.duoInstructionsFile`)
 * and `ai.learnings.file`, whose entries are kept for [apply] until the next [reset].
 */
internal class AiBaseReviewFiles {
    // limit: shared per extension, so concurrent runs in one process would race on it; upgrade path is per-run state
    private val learnings = atomic(emptyList<Learning>())

    /** Forgets the previous run's learnings. */
    fun reset() {
        learnings.value = emptyList()
    }

    /** The files [ai] asks to read from the MR base. */
    fun paths(ai: AiPluginConfig): Set<String> =
        setOfNotNull(
            DuoReviewInstructions.PATH.takeIf { ai.review.duoInstructionsFile },
            ai.learnings.file.takeIf { ai.learnings.enabled },
        )

    /** The review instructions in the base [files]; keeps the learnings for [apply]. */
    suspend fun read(
        files: Map<String, String>,
        ai: AiPluginConfig,
        yaml: ConfigFormatModule?,
        logger: Logger,
    ): List<ReviewInstruction> {
        learnings.value = AiLearnings.parse(files[ai.learnings.file], yaml, logger)
        return DuoReviewInstructions.parse(files[DuoReviewInstructions.PATH], yaml, logger) + AiLearnings.instructions(learnings.value)
    }

    /** [findings] with the kept learnings applied. */
    fun apply(findings: List<Finding>): List<Finding> = AiLearnings.apply(findings, learnings.value)
}
