package io.karandash.helpers.reporter

import io.karandash.models.finding.Attachment
import io.karandash.models.finding.Finding
import io.karandash.models.finding.FixSuggestion
import io.karandash.models.finding.Location
import io.karandash.models.finding.fixHintLines
import io.karandash.utils.finding.toDisplayString

/** [Attachment.filename] on one line: control characters (CR, LF, tab, ...) become spaces, so it cannot fake a header. */
val Attachment.displayName: String
    get() = filename
        .map { if (it.isISOControl()) ' ' else it }
        .joinToString("")
        .trim()
        .ifEmpty { "attachment" }

/**
 * The finding facts that have no field of their own in a plain-text medium: confidence, category, priority (when not
 * [Finding.DEFAULT_PRIORITY]), related findings, fingerprint and fix suggestions, as `name to value`.
 */
fun Finding.facts(): List<Pair<String, String>> =
    listOfNotNull(
        confidence?.let { "confidence" to it.toString() },
        category?.let { "category" to it.name.lowercase() },
        priority.takeIf { it != Finding.DEFAULT_PRIORITY }?.let { "priority" to it.toString() },
        relatedFindings.takeIf { it.isNotEmpty() }?.let { "relatedFindings" to it.joinToString(", ") },
        fingerprint?.let { "fingerprint" to it },
        fixSuggestions.takeIf { it.isNotEmpty() }?.let { "fixSuggestions" to it.joinToString("; ") { fix -> fix.describe() } },
    )

/** What this fix does, in a few words, in [language]: `add label 'x'`, `mark as draft`, `set title to "x"`, ... */
fun FixSuggestion.describe(language: String? = null): String {
    val lang = ViewLanguage.of(language)
    return when (this) {
        is FixSuggestion.AddLabel -> ViewText.ADD_LABEL.format(lang, label)
        is FixSuggestion.RemoveLabel -> ViewText.REMOVE_LABEL.format(lang, label)
        is FixSuggestion.SetDraft -> (if (draft) ViewText.MARK_DRAFT else ViewText.MARK_READY).text(lang)
        is FixSuggestion.SetTitle -> ViewText.SET_TITLE.format(lang, title)
        is FixSuggestion.SetDescription -> ViewText.UPDATE_DESCRIPTION.text(lang)
        is FixSuggestion.Custom -> description.ifBlank { type }
    }
}

/** [toDisplayString] with the end line of a multi-line file location: `path:42-48`. */
fun Location.toRangeDisplayString(): String {
    val ref = this as? Location.FileRef ?: return toDisplayString()
    val line = ref.line
    val end = ref.endLine
    return if (line != null && end != null && end > line) "${ref.path}:$line-$end" else toDisplayString()
}

/**
 * The finding as plain text: the message, its fix-hint lines in [language], the [details] paragraph when not blank,
 * then each attachment under a `--- name ---` line (name from [displayName]).
 */
fun Finding.plainTextBody(
    language: String? = null,
    details: String = "",
): String =
    buildString {
        append(message)
        fixHintLines(language).takeIf { it.isNotEmpty() }?.let { append("\n\n" + it.joinToString("\n")) }
        if (details.isNotBlank()) append("\n\n").append(details)
        for (attachment in attachments) {
            append("\n\n--- ${attachment.displayName} ---\n")
            append(attachment.content)
        }
    }
