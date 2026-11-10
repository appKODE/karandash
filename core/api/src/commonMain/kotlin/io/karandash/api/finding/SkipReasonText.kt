package io.karandash.api.finding

import io.karandash.models.finding.Translations

/**
 * The fixed skip reasons the engine, the AI rules and the rule gates write, in English; other languages from a pack.
 * `%1`, `%2` are values (ids, numbers, nested reasons). A reason is stored in English and translated when rendered,
 * by [skipReasonIn].
 */
internal enum class SkipReasonText(
    val en: String,
) {
    WHEN_NOT_MET("when condition not met"),
    WHEN_FAILED("when expression failed: %1"),
    BAIL_ON_FAIL("bail-on-fail triggered"),
    DISABLED_BY_POLICY("disabled by policy '%1'"),
    CONCERN_AREA_COVERED("concern area '%1' covered by pass-1"),
    MISSING_ENRICHMENT("missing required enrichment: %1"),
    PLATFORM_FIELDS("platform '%1' doesn't provide %2"),
    POLICY_NOT_APPLIED("policy '%1' override not applied (when: %2)"),
    DRY_RUN("dry run"),
    BUDGET_EXHAUSTED("budget exhausted"),
    PROFILE_TIMEOUT("execution profile totalTimeout reached"),
    GATED("gated: %1"),
    COST_CAP("skipped: budget — cost-budget.max-cost-per-run-usd cap of %1 USD reached (%2 spent)"),
    TOKEN_CAP("skipped: budget — %1 cap of %2 tokens reached (%3 used)"),
    DISABLED_BY_TRACK("disabled by track: %1"),
    CANARY_SHADOW("canary: shadow mode"),
    CANARY_ROLLOUT("canary: MR not in %1% rollout"),
    TOO_MANY_FILES("%1 files exceeds maxFiles=%2"),
    TOO_MANY_LINES("%1 changed lines exceeds maxTotalLines=%2"),
    TOO_MANY_COMMITS("%1 commits exceeds maxCommits=%2"),
    FALLBACK_COVERED("fallback — covered by %1"),
    PROVIDER_ERROR("provider error: %1"),
    TIMED_OUT("timed out"),
    CALL_FAILED("call failed, see the job log"),
    TRIVIAL_DIFF("trivial diff: %1"),
    WHITESPACE_ONLY("whitespace-only"),
    LOCKFILE("lockfile"),
    GENERATED("generated"),
    DOCS_ONLY("docs-only"),
    DISMISSED("dismissed %1 times previously"),
    NO_CHANGES("no changes since last review (snapshot: %1, loaded in %2, %3 unchanged file(s))"),
    NO_FILES_BLOOM("no files match patterns (bloom pre-screen)"),
    NO_FILES_MATCH("no files match patterns: %1"),
    TOO_FEW_FILES("too few changed files (%1 < %2)"),
    TOO_FEW_LINES("too few changed lines (%1 < %2)"),
    MISSING_LABEL("missing required label: %1"),
    SKIP_LABEL("skip label present"),
    BRANCH_SKIP("branch matches skip pattern"),
    TITLE_SKIP("title matches skip pattern: %1"),
}

/**
 * A skip [reason] in [language]: a [SkipReasonText] reason translated by a pack, its nested reasons and fixed words
 * too, `; ` lists (no template holds one) and `, ` lists piece by piece; a free-form reason, and any reason without a pack (English), as given.
 */
public fun skipReasonIn(
    reason: String,
    language: String?,
): String = if (Translations.pack(language) == null) reason else translatedReason(reason, language)

private fun translatedReason(
    reason: String,
    language: String?,
): String {
    if ("; " in reason) return reason.split("; ").joinToString("; ") { translatedReason(it, language) }
    for (text in SkipReasonText.entries) {
        val values = valuesOf(text.en, reason) ?: continue
        return values.foldIndexed(Translations.text(text.en, language)) { i, acc, v ->
            acc.replace("%${i + 1}", translatedReason(v, language))
        }
    }
    return if (", " in reason) reason.split(", ").joinToString(", ") { translatedReason(it, language) } else reason
}

/** The values of [text] that make up [template]'s placeholders, in order; null when [text] is not [template] filled. */
private fun valuesOf(
    template: String,
    text: String,
): List<String>? {
    val pieces = template.split(PLACEHOLDER)
    if (!text.startsWith(pieces.first()) || !text.endsWith(pieces.last())) return null
    if (pieces.size == 1) return if (text == template) emptyList() else null
    val end = text.length - pieces.last().length
    val values = mutableListOf<String>()
    var start = pieces.first().length
    for (piece in pieces.subList(1, pieces.lastIndex)) {
        val at = text.indexOf(piece, start)
        if (at < 0 || at > end) return null
        values += text.substring(start, at)
        start = at + piece.length
    }
    if (start > end) return null
    values += text.substring(start, end)
    return values.takeIf { v -> v.none { it.isEmpty() } }
}

private val PLACEHOLDER = Regex("%\\d")
