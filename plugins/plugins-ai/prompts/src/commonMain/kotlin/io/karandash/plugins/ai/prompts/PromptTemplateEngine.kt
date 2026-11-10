package io.karandash.plugins.ai.prompts

import io.karandash.models.MergeRequest
import io.karandash.utils.parseCsvList
import kotlinx.serialization.Serializable

/**
 * Template engine for AI prompts supporting variable substitution and basic control flow.
 *
 * **Variable substitution:** `{{mr.title}}`, `{{ mr.labels }}` - replaced with MR field values.
 *
 * **Conditional blocks:**
 * ```
 * {% if mr.draft == true %}
 * This is a draft MR. Focus on structural feedback only.
 * {% endif %}
 * ```
 *
 * **Supported condition operators:** `==`, `!=`, `contains`, `>`, `<`, `>=`, `<=`
 *
 * **Loop blocks:**
 * ```
 * {% for file in mr.files %}
 * - {{file}}
 * {% endfor %}
 * ```
 * Loop variables reference comma-separated list variables (e.g. `mr.files`, `mr.labels`).
 *
 * Undefined variables are left as-is and reported once per render through `warn` (a typo such as `{{mr.titel}}` would
 * otherwise reach the model unnoticed). Failed conditions leave their block in place.
 *
 * MR text (title, description, branches, labels, author, file paths) is substituted inside an
 * `<untrusted-mr-*>` fence, so a prompt template never hands it to the model as instructions.
 * [renderSplit] goes further for a system prompt: the text keeps only `<untrusted-mr-title/>`-style references and the
 * fenced values come back separately, for the user message.
 */
internal object PromptTemplateEngine {
    private const val ESCAPED_OPEN = "\uFFF0\uFFF0"
    private const val ESCAPED_CLOSE = "\uFFF1\uFFF1"
    private const val ESCAPED_BLOCK_OPEN = "\uFFF2"
    private const val ESCAPED_BLOCK_CLOSE = "\uFFF3"

    private val IF_BLOCK_REGEX = Regex(
        """\{%\s*if\s+(.+?)\s*%\}([\s\S]*?)\{%\s*endif\s*%\}""",
        RegexOption.MULTILINE,
    )

    private val FOR_BLOCK_REGEX = Regex(
        """\{%\s*for\s+(\w+)\s+in\s+(\S+)\s*%\}([\s\S]*?)\{%\s*endfor\s*%\}""",
        RegexOption.MULTILINE,
    )

    /**
     * Prevents user-controlled values from being interpreted as template syntax by replacing
     * `{{`, `}}`, `{%`, and `%}` with private-use unicode placeholders before substitution.
     */
    private fun escapeTemplateChars(value: String): String =
        value
            .replace("{{", ESCAPED_OPEN)
            .replace("}}", ESCAPED_CLOSE)
            .replace("{%", ESCAPED_BLOCK_OPEN)
            .replace("%}", ESCAPED_BLOCK_CLOSE)

    /** Reverses the escaping applied by [escapeTemplateChars]. */
    private fun unescapeTemplateChars(value: String): String =
        value
            .replace(ESCAPED_OPEN, "{{")
            .replace(ESCAPED_CLOSE, "}}")
            .replace(ESCAPED_BLOCK_OPEN, "{%")
            .replace(ESCAPED_BLOCK_CLOSE, "%}")

    /**
     * Renders a template by processing control-flow blocks and substituting `{{key}}` placeholders.
     *
     * Processing order: conditionals → loops → variable substitution.
     *
     * Variable values are escaped before substitution so that user-controlled content containing
     * `{{...}}` or `{%...%}` syntax is never re-processed as template directives.
     *
     * @param template The prompt template
     * @param mr The merge request providing context variables
     * @param extra Additional user-defined variables that override MR-derived ones
     * @param warn Receives one message naming the `{{...}}` placeholders no variable resolved
     * @return The rendered template
     */
    fun render(
        template: String,
        mr: MergeRequest,
        extra: Map<String, String> = emptyMap(),
        warn: (String) -> Unit = {},
    ): String = renderTemplate(template, mr, extra, split = false, warn).text

    /**
     * Renders [template] for a system prompt: each MR value becomes an `<untrusted-mr-title/>`-style reference, and
     * [RenderedPrompt.mrData] holds the referenced values in their `<untrusted-mr-*>` fences, for the user message.
     * A loop over an MR list renders its body once, with the loop variable as the reference to the whole list.
     */
    fun renderSplit(
        template: String,
        mr: MergeRequest,
        extra: Map<String, String> = emptyMap(),
        warn: (String) -> Unit = {},
    ): RenderedPrompt = renderTemplate(template, mr, extra, split = true, warn)

    private val UNRESOLVED_REGEX = Regex("""\{\{\s*([^{}]+?)\s*\}\}""")

    private fun renderTemplate(
        template: String,
        mr: MergeRequest,
        extra: Map<String, String>,
        split: Boolean,
        warn: (String) -> Unit,
    ): RenderedPrompt {
        val variables = buildVariables(mr) + extra.mapValues { escapeTemplateChars(it.value) }
        val untrusted = UNTRUSTED_KEYS - extra.keys
        val referenced = linkedSetOf<String>()
        val onReference: ((String) -> Unit)? = if (split) referenced::add else null
        var result = renderConditionals(template, variables)
        result = renderLoops(result, variables, untrusted, onReference)
        for ((key, raw) in variables) {
            val placeholders = listOf("{{$key}}", "{{ $key }}")
            if (placeholders.none { it in result }) continue
            val value = when {
                key !in untrusted -> raw
                onReference == null -> fence(key, raw)
                else -> reference(key).also { onReference(key) }
            }
            placeholders.forEach { result = result.replace(it, value) }
        }
        // Substituted values are escaped, so whatever `{{...}}` is left came from the template itself.
        val unresolved = UNRESOLVED_REGEX
            .findAll(result)
            .map { it.groupValues[1] }
            .distinct()
            .sorted()
            .toList()
        if (unresolved.isNotEmpty()) warn("Prompt template placeholders not resolved (left as-is): ${unresolved.joinToString(", ")}")
        val mrData = referenced.joinToString("\n") { key -> fence(key, variables.getValue(key)) }
        return RenderedPrompt(unescapeTemplateChars(result), unescapeTemplateChars(mrData).ifEmpty { null })
    }

    /** MR-derived free text; the engine fences it as untrusted data. */
    private val UNTRUSTED_KEYS = setOf(
        "mr.title",
        "mr.description",
        "mr.sourceBranch",
        "mr.targetBranch",
        "mr.labels",
        "mr.author",
        "mr.files",
    )

    /** Wraps [value] in an `<untrusted-…>` tag named after [key]; a fence tag inside the value is defused. */
    private fun fence(
        key: String,
        value: String,
    ): String {
        val tag = fenceTag(key)
        return "<$tag>${value.replace("untrusted-", "untrusted_", ignoreCase = true)}</$tag>"
    }

    /** The `<untrusted-…/>` reference a split system prompt carries in place of [key]'s value. */
    private fun reference(key: String): String = "<${fenceTag(key)}/>"

    private fun fenceTag(key: String): String = "untrusted-" + key.replace('.', '-')

    /**
     * Evaluates `{% if CONDITION %}...{% endif %}` blocks.
     *
     * Blocks whose condition evaluates to true are replaced with their content.
     * Blocks whose condition is false are removed. Unparseable conditions are left as-is.
     */
    internal fun renderConditionals(
        template: String,
        variables: Map<String, String>
    ): String =
        IF_BLOCK_REGEX.replace(template) { match ->
            val condition = match.groupValues[1].trim()
            val body = match.groupValues[2]
            when (evaluateCondition(condition, variables)) {
                true -> body
                false -> ""
                null -> match.value // leave unchanged if condition can't be parsed
            }
        }

    /**
     * Evaluates `{% for VAR in LIST %}...{% endfor %}` blocks.
     *
     * LIST must be a comma-separated variable (e.g. `mr.files`, `mr.labels`).
     * Each iteration makes VAR available as `{{VAR}}` inside the body.
     * Blocks referencing unknown list variables are removed.
     */
    internal fun renderLoops(
        template: String,
        variables: Map<String, String>,
        untrusted: Set<String> = emptySet(),
        onReference: ((String) -> Unit)? = null,
    ): String =
        FOR_BLOCK_REGEX.replace(template) { match ->
            val loopVar = match.groupValues[1]
            val listVar = match.groupValues[2]
            val body = match.groupValues[3]
            val listValue = variables[listVar] ?: return@replace ""
            if (onReference != null && listVar in untrusted) {
                onReference(listVar)
                return@replace body.replace("{{$loopVar}}", reference(listVar)).replace("{{ $loopVar }}", reference(listVar))
            }
            val items = listValue.parseCsvList()
            items.joinToString("") { raw ->
                val item = if (listVar in untrusted) fence(listVar, raw) else raw
                body.replace("{{$loopVar}}", item).replace("{{ $loopVar }}", item)
            }
        }

    /**
     * Evaluates a simple condition expression against the variables map.
     *
     * @return true/false if evaluable, null if the expression cannot be parsed
     */
    internal fun evaluateCondition(
        condition: String,
        variables: Map<String, String>
    ): Boolean? =
        tryEvaluateContains(condition, variables)
            ?: tryEvaluateComparison(condition, variables)

    private fun tryEvaluateContains(
        condition: String,
        variables: Map<String, String>
    ): Boolean? {
        val parts = condition.split(" contains ", ignoreCase = true)
        if (parts.size != 2) return null
        val varValue = variables[parts[0].trim()] ?: return null
        val searchValue = parts[1].trim().removeSurrounding("\"").removeSurrounding("'")
        return varValue.contains(searchValue, ignoreCase = true)
    }

    private fun tryEvaluateComparison(
        condition: String,
        variables: Map<String, String>
    ): Boolean? {
        val operators = listOf(">=", "<=", "!=", "==", ">", "<")
        for (op in operators) {
            val idx = condition.indexOf(op)
            if (idx < 0) continue
            val lhsKey = condition.substring(0, idx).trim()
            val rhs = condition
                .substring(idx + op.length)
                .trim()
                .removeSurrounding("\"")
                .removeSurrounding("'")
            val lhsValue = variables[lhsKey] ?: return null
            return evaluateOp(lhsValue, op, rhs)
        }
        return null
    }

    private fun evaluateOp(
        lhs: String,
        op: String,
        rhs: String
    ): Boolean? {
        // Try numeric comparison first
        val lhsNum = lhs.toDoubleOrNull()
        val rhsNum = rhs.toDoubleOrNull()
        if (lhsNum != null && rhsNum != null) {
            return when (op) {
                "==" -> lhsNum == rhsNum
                "!=" -> lhsNum != rhsNum
                ">" -> lhsNum > rhsNum
                "<" -> lhsNum < rhsNum
                ">=" -> lhsNum >= rhsNum
                "<=" -> lhsNum <= rhsNum
                else -> null
            }
        }
        // Fall back to string comparison
        return when (op) {
            "==" -> lhs.equals(rhs, ignoreCase = true)
            "!=" -> !lhs.equals(rhs, ignoreCase = true)
            else -> null
        }
    }

    internal fun buildVariables(mr: MergeRequest): Map<String, String> =
        buildMap {
            put("mr.title", escapeTemplateChars(mr.title))
            put("mr.description", escapeTemplateChars(mr.description ?: ""))
            put("mr.sourceBranch", escapeTemplateChars(mr.sourceBranch))
            put("mr.targetBranch", escapeTemplateChars(mr.targetBranch))
            put("mr.draft", mr.draft.toString())
            put("mr.labels", escapeTemplateChars(mr.labels.joinToString(", ")))
            put("mr.changes.count", mr.changes.size.toString())
            put("mr.commits.count", mr.commits.size.toString())
            put("mr.author", escapeTemplateChars(mr.author.name))
            put("mr.additions", mr.changes.sumOf { it.additions }.toString())
            put("mr.deletions", mr.changes.sumOf { it.deletions }.toString())
            put("mr.files", escapeTemplateChars(mr.changes.joinToString(", ") { it.path }))
        }
}

/**
 * A prompt rendered by [PromptTemplateEngine.renderSplit]: [text] for the system prompt, and [mrData], the fenced MR
 * values it references, for the user message (`null` when it references none).
 */
@Serializable
data class RenderedPrompt(
    val text: String,
    val mrData: String?,
)
