package io.karandash.cli.pipeline

import io.karandash.api.exception.ConfigParseException
import io.karandash.api.finding.BaselineComparison
import io.karandash.models.finding.Finding
import io.karandash.models.support.KarandashJson
import io.karandash.models.support.WellKnownMetadataKeys
import io.karandash.normalize.ConfigKeyNormalizer.kebabToCamelCase
import io.karandash.platform.createDirectories
import io.karandash.platform.readText
import io.karandash.platform.safeParent
import io.karandash.platform.writeText
import io.karandash.utils.finding.FindingFingerprint
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlin.time.Clock

/** The on-disk baseline: fingerprints of the findings a project has agreed to live with. */
@Serializable
internal data class BaselineFile(
    val fingerprints: List<String> = emptyList(),
    /** Unix epoch seconds of the `--update-baseline` run that wrote the file; 0 in a file written before it was recorded. */
    val createdAt: Long = 0,
)

/** Outcome of [FindingSuppression.apply]: what stays reported, plus the baseline comparison when a baseline is in play. */
@Serializable
internal data class SuppressionResult(
    val findings: List<Finding>,
    val comparison: BaselineComparison?,
    val disabledRules: Set<String>,
)

/**
 * Drops findings the run was told to ignore: rules named by `karandash-disable <rule>[, <rule>]` in the MR
 * description, and findings already recorded in a `--baseline` file. With `--update-baseline` the file is rewritten
 * from the current findings instead of being applied.
 */
internal object FindingSuppression {
    private const val MARKER = "karandash-disable"

    /**
     * Rule ids named by `karandash-disable` (or `karandash-disable:`) markers in [description]; a comma continues the
     * list on the same line. A token that is not a rule id (`[A-Za-z0-9_-]+`) ends the list.
     */
    fun disabledRules(description: String?): Set<String> {
        if (description == null || MARKER !in description) return emptySet()
        val rules = linkedSetOf<String>()
        for (line in description.lineSequence()) {
            if (MARKER !in line) continue
            for (chunk in line.substringAfter(MARKER).removePrefix(":").split(',')) {
                val words = chunk.trim().split(' ', '\t').filter { it.isNotEmpty() }
                if (words.isEmpty() || !isRuleId(words.first())) break
                rules += words.first()
                if (words.size > 1) break
            }
        }
        return rules
    }

    private fun isRuleId(token: String): Boolean = token.all { it.isLetterOrDigit() || it == '-' || it == '_' }

    /**
     * Baseline keys of [finding], preferred first: its line-independent anchor fingerprint when the engine could stamp
     * one, then its fingerprint (which keys the line). A finding matches the baseline when any key is recorded, so a
     * baseline written before anchors existed still matches. The anchor holds while the anchored line's text is
     * unchanged; it is absent for a finding without a line or on an unanchorable line, and shifts when an identical
     * line is added above (occurrence index).
     */
    fun keys(finding: Finding): List<String> =
        listOfNotNull(
            finding.metadata[WellKnownMetadataKeys.ANCHOR_FINGERPRINT],
            finding.fingerprint ?: FindingFingerprint.generate(finding.rule, finding.location, finding.message),
        )

    /** The key [apply] records for [finding] when it writes a baseline. */
    fun key(finding: Finding): String = keys(finding).first()

    fun apply(
        findings: List<Finding>,
        description: String?,
        baselinePath: String?,
        updateBaseline: Boolean,
    ): SuppressionResult {
        val disabled = disabledRules(description)
        // `size-limits`, `size_limits` and `sizeLimits` name the same rule, as in config keys
        val disabledKeys = disabled.map(::kebabToCamelCase).toSet()
        val enabled = findings.filter { kebabToCamelCase(it.rule) !in disabledKeys }
        if (baselinePath == null) {
            if (updateBaseline) {
                throw ConfigParseException(
                    "--update-baseline needs a baseline file",
                    hint = "Pass --baseline <file> or set 'baseline: <file>' in the config.",
                )
            }
            return SuppressionResult(enabled, null, disabled)
        }
        val path = Path(baselinePath)
        if (updateBaseline) {
            path.safeParent?.let { createDirectories(it) }
            path.writeText(
                KarandashJson.Default.encodeToString(
                    BaselineFile.serializer(),
                    BaselineFile(enabled.map(::key).distinct().sorted(), Clock.System.now().epochSeconds)
                )
            )
            return SuppressionResult(enabled, null, disabled)
        }
        val baseline = if (SystemFileSystem.exists(path)) readBaseline(path, baselinePath) else BaselineFile()
        val known = baseline.fingerprints.toSet()
        val (unchanged, fresh) = enabled.partition { finding -> keys(finding).any { it in known } }
        val comparison = BaselineComparison(
            baselineTimestamp = baseline.createdAt,
            newFindings = fresh.size,
            resolvedFindings = (known - enabled.flatMap(::keys).toSet()).size,
            unchangedFindings = unchanged.size,
        )
        return SuppressionResult(fresh, comparison, disabled)
    }

    private fun readBaseline(
        path: Path,
        baselinePath: String,
    ): BaselineFile =
        try {
            KarandashJson.Default.decodeFromString(BaselineFile.serializer(), path.readText())
        } catch (e: SerializationException) {
            throw malformed(baselinePath, e)
        } catch (e: IllegalArgumentException) {
            throw malformed(baselinePath, e)
        }

    private fun malformed(
        baselinePath: String,
        cause: Exception,
    ) = ConfigParseException(
        "Baseline file '$baselinePath' is not a valid baseline: ${cause.message.orEmpty().lineSequence().first()}",
        hint = "Regenerate it with --update-baseline.",
    )
}
