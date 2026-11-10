package io.karandash.plugins.ai.core.config

import io.karandash.api.config.PreserveKeys
import kotlinx.serialization.Serializable

/**
 * Parses inline override tags from MR descriptions.
 *
 * Authors embed override directives in the MR description to request self-service exceptions.
 * Format: `<!-- karandash-override rule: <rule> <field>: <value> reason: "<reason>" -->`
 *
 * Example:
 * ```
 * <!-- karandash-override rule: size-limits maxAdditions: 600 reason: "generated file" -->
 * ```
 */
object InlineMrOverrideParser {
    private val OVERRIDE_REGEX = Regex(
        """<!--\s*karandash-override\s+rule:\s*(\S+)\s+(.*?)reason:\s*"([^"]*?)"\s*-->""",
        RegexOption.IGNORE_CASE,
    )
    private val FIELD_REGEX = Regex("""(\w[\w-]*):\s*(\S+)""")
    private val TAG_REGEX = Regex(
        """<!--\s*@karandash\s+([\w-]+)(?:\s+([^\s:]+))?\s*(?::\s*(.*?))?\s*-->""",
        RegexOption.IGNORE_CASE,
    )

    // At the start of a reply line (leading spaces allowed), so neither a `>` quote nor a mention mid-sentence counts
    private val THREAD_COMMAND_REGEX =
        Regex("""^[ \t]*@karandash[ \t]+([\w-]+)""", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))

    /**
     * The `@karandash <command>` a reviewer wrote at the start of a line of a reply to a karandash inline thread, or
     * null when no line starts with one. A word that is not a [ThreadCommand] gives [ThreadCommand.UNKNOWN].
     *
     * One command per reply: the first line that starts with a command wins and later command lines of the same reply
     * are ignored. Write each command in its own reply.
     */
    fun parseThreadCommand(reply: String): ThreadCommand? {
        val word = THREAD_COMMAND_REGEX
            .find(reply)
            ?.groupValues
            ?.get(1)
            ?.lowercase() ?: return null
        return ThreadCommand.entries.firstOrNull { word in it.words } ?: ThreadCommand.UNKNOWN
    }

    private val ASK_REGEX =
        Regex(
            """^[ \t]*@karandash[ \t]+ask\b[ \t:]*([\s\S]*)""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE),
        )

    /** The question after `@karandash ask` to the end of [reply], or null when there is no `ask` line or no text. */
    fun askQuestion(reply: String): String? =
        ASK_REGEX
            .find(reply)
            ?.groupValues
            ?.get(1)
            ?.trim()
            ?.ifEmpty { null }

    /**
     * Parses `<!-- @karandash <tag> [target][: reason] -->` tags from the MR description, in document order.
     * The tag name is lowercased; target is null and reason empty when absent.
     */
    fun parseTags(description: String?): List<DescriptionTag> =
        description
            ?.let { text -> TAG_REGEX.findAll(text) }
            ?.map { m -> DescriptionTag(m.groupValues[1].lowercase(), m.groupValues[2].ifEmpty { null }, m.groupValues[3]) }
            ?.toList()
            .orEmpty()

    /**
     * Parses all inline override tags found in the MR description.
     *
     * @param description The MR description text, or null.
     * @return List of parsed overrides; empty when description is null or contains no tags.
     */
    fun parse(description: String?): List<InlineMrOverride> {
        if (description == null) return emptyList()
        return OVERRIDE_REGEX
            .findAll(description)
            .map { match ->
                val rule = match.groupValues[1]
                val fieldsStr = match.groupValues[2]
                val reason = match.groupValues[3]
                val fields = FIELD_REGEX
                    .findAll(fieldsStr)
                    .associate { it.groupValues[1] to it.groupValues[2] }
                    .filterKeys { it != "rule" }
                InlineMrOverride(rule = rule, fields = fields, reason = reason)
            }.toList()
    }

    /**
     * Validates overrides against the set of allowed overrides configured by the team.
     *
     * An override is rejected when its rule is not in the allowed list, when it sets a field outside a
     * non-empty [AllowedOverride.fields], when a numeric field exceeds the configured maximum value, or when its [AllowedOverride.requireReason]
     * is set and the reason is blank.
     *
     * @param overrides Parsed overrides to validate.
     * @param allowedOverrides Team-configured ceilings for each rule.
     * @return Each override paired with a validity flag and optional rejection reason.
     */
    fun validate(
        overrides: List<InlineMrOverride>,
        allowedOverrides: List<AllowedOverride>
    ): List<ValidatedOverride> =
        overrides.map { override ->
            val allowed = allowedOverrides.find { it.rule == override.rule }
            if (allowed == null) {
                ValidatedOverride(override, valid = false, rejectionReason = "rule not in allowed overrides")
            } else if (allowed.requireReason && override.reason.isBlank()) {
                ValidatedOverride(override, valid = false, rejectionReason = "a reason is required")
            } else {
                val violations = override.fields.mapNotNull { (field, value) ->
                    val maxValue = allowed.maxValues[field]
                    if (allowed.fields.isNotEmpty() && field !in allowed.fields) {
                        "$field is not an allowed field"
                    } else if (maxValue != null && value.toIntOrNull() != null && value.toInt() > maxValue.toInt()) {
                        "$field=$value exceeds max $maxValue"
                    } else {
                        null
                    }
                }
                if (violations.isEmpty()) {
                    ValidatedOverride(override, valid = true)
                } else {
                    ValidatedOverride(override, valid = false, rejectionReason = violations.joinToString("; "))
                }
            }
        }
}

/** An override directive parsed from an MR description. */
@Serializable
data class InlineMrOverride(
    /** The rule ID this override targets. */
    val rule: String,
    /** Field/value pairs extracted from the override tag. */
    val fields: Map<String, String>,
    /** The author-supplied reason for the override. */
    val reason: String,
)

/** Team-configured ceiling for a specific rule's inline overrides. */
@Serializable
data class AllowedOverride(
    /** The rule ID this ceiling applies to. */
    val rule: String,
    /** Field names that authors are permitted to override; empty allows every field. */
    val fields: List<String> = emptyList(),
    /** Maximum numeric values per field name. */
    @PreserveKeys val maxValues: Map<String, String> = emptyMap(),
    /** When true, the override tag must include a non-empty reason string. */
    val requireReason: Boolean = true,
)

/** An [InlineMrOverride] paired with its validation outcome. */
@Serializable
data class ValidatedOverride(
    /** The override that was evaluated. */
    val override: InlineMrOverride,
    /** True when the override passes all ceiling checks. */
    val valid: Boolean,
    /** Human-readable reason for rejection, or null when valid. */
    val rejectionReason: String? = null,
)

/** A `<!-- @karandash <tag> [target][: reason] -->` tag from the MR description. */
@Serializable
data class DescriptionTag(
    /** Lowercased tag name, e.g. `override` or `grace-period-start`. */
    val tag: String,
    /** The word after the tag (a rule id for override tags), or null. */
    val target: String? = null,
    /** Text after the colon, or empty. */
    val reason: String = "",
)

/**
 * `ai.inlineOverrides`: `<!-- @karandash <overrideTag> <rule>: <reason> -->` tags (also `waive` and `exempt`)
 * downgrade that rule's findings to INFO. `enabled: false` turns every inline override off, the
 * `karandash-override` form included.
 */
@Serializable
data class InlineOverridesConfig(
    val enabled: Boolean = true,
    val overrideTag: String = "override",
    /**
     * Tags are honored only when the MR has at least one approval. Not `fileChanges.requireApproval`, a list of
     * users (docs/rules/file-changes.md).
     */
    val requireApproval: Boolean = false,
    /** Log each overridden finding and record `overrideReason` in its metadata. */
    val auditLog: Boolean = false,
    /**
     * when set, only overrides of these rules apply, within their `maxValues` and with a reason when
     * `requireReason` (default true); a rejected override is not applied and is logged. Empty allows every rule.
     */
    val allowed: List<AllowedOverride> = emptyList(),
)

/** A reviewer's `@karandash <command>` in a reply to an inline thread ([InlineMrOverrideParser.parseThreadCommand]). */
enum class ThreadCommand(
    /** Spellings of the command, lowercased. */
    val words: Set<String>,
    /** The command asks the bot a question instead of dismissing the finding. */
    val answers: Boolean = false,
) {
    /** Not a problem here: drop the finding and resolve the thread. */
    IGNORE(setOf("ignore")),

    /** A real issue the team will not fix: drop the finding and resolve the thread. */
    WONTFIX(setOf("wontfix", "wont-fix")),

    /** Handled: resolve the thread and do not raise the finding again. */
    RESOLVE(setOf("resolve")),

    /** Explain how the finding came about: a deterministic reply from the run's provenance. */
    WHY(setOf("why"), answers = true),

    /** Answer the question after the command, with one model call; off unless `ai.thread-commands.ask`. */
    ASK(setOf("ask"), answers = true),

    /** A mention with a word that is no command; answered once with help. */
    UNKNOWN(emptySet()),
}
