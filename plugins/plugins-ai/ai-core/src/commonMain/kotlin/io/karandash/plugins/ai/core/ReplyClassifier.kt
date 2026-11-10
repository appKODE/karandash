package io.karandash.plugins.ai.core

import kotlinx.serialization.Serializable

/** Verdict a human reply gives on a bot finding. */
enum class ReplyVerdict { ACCEPTED, REJECTED, UNKNOWN }

/** Net non-bot reaction on the bot note a reply answers. */
enum class ReplyReaction {
    UP,
    DOWN,
    ;

    companion object {
        private val UP_AWARDS = setOf("thumbsup", "white_check_mark")
        private val DOWN_AWARDS = setOf("thumbsdown")
        private const val TONE = "_tone"

        /** The net reaction of award [names] (skin-tone suffix `_toneN` ignored); null for none or both. */
        fun of(names: Collection<String>): ReplyReaction? {
            val base = names.map { it.substringBefore(TONE) }
            val up = base.any { it in UP_AWARDS }
            val down = base.any { it in DOWN_AWARDS }
            return when {
                up == down -> null
                up -> UP
                else -> DOWN
            }
        }
    }
}

/**
 * A human reply plus the structural signals known about it; `null` means the signal is unavailable.
 *
 * @property fixCommitCited whether a commit sha cited in the reply belongs to the merge request
 * @property reaction the net non-bot reaction on the bot note
 */
@Serializable
data class ReplyEvidence(
    val text: String,
    val fixCommitCited: Boolean? = null,
    val reaction: ReplyReaction? = null,
)

/**
 * RU/EN classifier for human replies to bot findings, from the reply text plus optional structural
 * [ReplyEvidence]. Conservative by design: a question, a quote of the bot text (`> ...`), a negated keyword
 * or a reply hitting both keyword lists is [ReplyVerdict.UNKNOWN].
 *
 * - Double-quoted and backtick-quoted text counts only for the commit shas in it (`Fixed the "not a bug" note`).
 * - A [REBUTTAL_MARKERS] phrase ("already there", "no change needed", "not valid", "так задумано") rejects
 *   the reply even next to a later accept word ("Not valid, thanks"), but not after a fix or accept word
 *   ("Good catch, it's not a race anymore"); [POLITE_KEYWORDS] never make a reply mixed ("Thanks, false positive").
 * - An accept-only reply is [ReplyVerdict.UNKNOWN] when it is longer than [MAX_ACCEPTED_LENGTH] chars, has
 *   "already"/"уже" within [ALREADY_WINDOW] words of an accept keyword, or hits only
 *   [WEAK_ACCEPTED_KEYWORDS] (they still make a reply mixed, so dismissals are unaffected) - unless it cites
 *   a fix: a [FIX_WORDS] word followed within [FIX_CITE_WINDOW] words by an MR commit sha ("Fixed in 1a2b3c4").
 * - Evidence: only an MR commit sha (`fixCommitCited == true`) is a fix citation, so with no commit list a sha
 *   proves nothing; an MR commit sha
 *   in a short reply without keywords ("Добавлен clamp в 1a2b3c4") is [ReplyVerdict.ACCEPTED]; a reaction
 *   against the text verdict makes it [ReplyVerdict.UNKNOWN]. A code change at the anchor is deliberately not
 *   an input: on the hand-labelled replies, no change at the anchor held for 54 accepted replies (fixes land
 *   outside the 3-line window), so it cannot veto a text accept.
 *
 * Keywords match whole words (phrases as consecutive words); all lists live here so the classifier can be
 * hand-validated (target: dismissal precision >= 0.90, accepted precision >= 0.85).
 */
object ReplyClassifier {
    val ACCEPTED_KEYWORDS: List<String> = listOf(
        "fixed",
        "fix it",
        "will fix",
        "done",
        "good catch",
        "nice catch",
        "thanks",
        "thank you",
        "agree",
        "addressed",
        "updated",
        "resolved",
        "applied",
        "исправил",
        "исправила",
        "исправлено",
        "исправлю",
        "поправил",
        "поправила",
        "поправлю",
        "поправлено",
        "пофиксил",
        "пофиксила",
        "пофикшено",
        "сделал",
        "сделала",
        "сделано",
        "согласен",
        "согласна",
        "спасибо",
        "хорошее замечание",
        "верно",
        "валидно",
        "по делу",
        "справедливо",
        "дело говорит",
        "соглашусь",
        "точно",
        "убрал",
        "убрала",
        "учел",
        "учёл",
        "добавил",
        "добавила",
    )

    val REJECTED_KEYWORDS: List<String> = listOf(
        "false positive",
        "not a bug",
        "not an issue",
        "not relevant",
        "irrelevant",
        "wrong",
        "incorrect",
        "nonsense",
        "won't fix",
        "wont fix",
        "by design",
        "intended",
        "intentional",
        "intentionally",
        "as designed",
        "ignore",
        "disagree",
        "invalid",
        "hallucination",
        "no need",
        "не баг",
        "не проблема",
        "не актуально",
        "неактуально",
        "ложное срабатывание",
        "ложная тревога",
        "ошибка бота",
        "бред",
        "неверно",
        "неправда",
        "не согласен",
        "не согласна",
        "не нужно",
        "не надо",
        "так задумано",
        "так и задумано",
        "специально",
        "намеренно",
        "игнор",
        "мимо",
    )

    /** Accept keywords too vague to accept on their own ("верно" often means "the code is fine"). */
    val WEAK_ACCEPTED_KEYWORDS: List<String> = listOf("agree", "addressed", "resolved", "верно", "все верно", "валидно")

    /**
     * Phrases that reject a reply even next to a later accept word: the reply argues the finding away. Single words
     * that are [REJECTED_KEYWORDS] are not repeated here, so next to an accept word they make a reply mixed.
     */
    val REBUTTAL_MARKERS: List<String> = listOf(
        "already addressed",
        "already handled",
        "already there",
        "already in place",
        "already guarded",
        "already satisfied",
        "no change needed",
        "no changes needed",
        "by design",
        "not valid",
        "not a race",
        "works as intended",
        "not blocking",
        "duplicate of",
        "not confirmed",
        "not exploitable",
        "superseded",
        "no longer applicable",
        "already present",
        "так задумано",
        "так и задумано",
        "уже есть",
        "уже реализовано",
        "в коде все верно",
        "технически верно но",
        "не блокирует",
        "невалидно",
        "ложное",
        "не применяю",
        "не подтверждается",
        "нет необходимости",
        "уже присутствует",
        "не относится",
        "намеренный",
    )

    /** Fix verbs that, followed by a commit sha, cite the fixing commit. */
    val FIX_WORDS: List<String> = listOf(
        "fixed",
        "fix",
        "done",
        "applied",
        "updated",
        "исправил",
        "исправила",
        "исправлено",
        "поправил",
        "поправила",
        "поправлено",
        "пофиксил",
        "пофиксила",
        "пофикшено",
        "сделал",
        "сделала",
        "добавил",
        "добавила",
        "добавлено",
        "сделано",
    )

    /** Accept keywords that are politeness: next to a reject keyword they do not make a reply mixed. */
    val POLITE_KEYWORDS: List<String> = listOf("thanks", "thank you", "спасибо")

    /** Words that, near an accept keyword, mean the change predates the finding. */
    val ALREADY: List<String> = listOf("already", "уже")

    const val MAX_ACCEPTED_LENGTH = 300
    const val ALREADY_WINDOW = 3
    const val FIX_CITE_WINDOW = 5

    /** Words that negate a keyword starting within the next [NEGATION_WINDOW] words. */
    val NEGATIONS: List<String> = listOf("not", "no", "never", "don't", "didn't", "isn't", "haven't", "не", "нет", "ни")

    private const val NEGATION_WINDOW = 2
    private const val DONE = "done"
    private const val MIN_SHA = 7
    private const val MAX_SHA = 40

    private val accepted = ACCEPTED_KEYWORDS.map(::words)
    private val rejected = REJECTED_KEYWORDS.map(::words)
    private val negations = NEGATIONS.toSet()
    private val weak = WEAK_ACCEPTED_KEYWORDS.map(::words)
    private val rebuttals = REBUTTAL_MARKERS.map(::words)
    private val polite = POLITE_KEYWORDS.map(::words)
    private val quotes = mapOf('"' to '"', '`' to '`', '“' to '”', '«' to '»')
    private val fixWords = FIX_WORDS.toSet()
    private val already = ALREADY.toSet()

    /** Negations that answer the finding ("No, already there") rather than negate the marker after them. */
    private val answers = setOf("no", "нет")

    fun classify(reply: String): ReplyVerdict = classify(ReplyEvidence(reply))

    fun classify(evidence: ReplyEvidence): ReplyVerdict {
        val reply = evidence.text
        if (asksQuestion(reply) || reply.lineSequence().any { it.trimStart().startsWith(">") }) return ReplyVerdict.UNKNOWN
        val text = words(unquoted(reply))
        val verdict = if (rebutted(text, evidence)) ReplyVerdict.REJECTED else keywordVerdict(text, evidence)
        return withReaction(verdict, evidence.reaction)
    }

    /** [verdict], or [ReplyVerdict.UNKNOWN] when [reaction] contradicts it (a thumbs down on an accept, up on a reject). */
    fun withReaction(
        verdict: ReplyVerdict,
        reaction: ReplyReaction?,
    ): ReplyVerdict {
        val against = when (verdict) {
            ReplyVerdict.ACCEPTED -> ReplyReaction.DOWN
            ReplyVerdict.REJECTED -> ReplyReaction.UP
            ReplyVerdict.UNKNOWN -> null
        }
        return if (against != null && reaction == against) ReplyVerdict.UNKNOWN else verdict
    }

    /**
     * Whether a sha cited in [reply] prefixes one of the MR's [commits]: null when the reply cites none or the commit
     * list is unknown (null or empty), so a bare hex word never counts as a fix commit on its own.
     */
    fun fixCommitCited(
        reply: String,
        commits: Set<String>?,
    ): Boolean? {
        val cited = citedShas(reply)
        if (cited.isEmpty() || commits.isNullOrEmpty()) return null
        return cited.any { sha -> commits.any { it.lowercase().startsWith(sha) } }
    }

    /**
     * A [REBUTTAL_MARKERS] phrase, not negated, with no accept signal before it: a non-polite accept keyword, a
     * [FIX_WORDS] word or an MR commit sha; a bare "done" is no signal. A marker holding a negation or "already"
     * ("no change needed", "уже есть") is not negated by a leading answer "No," / "Нет,", but is by "not" / "не".
     */
    private fun rebutted(
        text: List<String>,
        evidence: ReplyEvidence,
    ): Boolean {
        val rejectHits = occurrences(text, rejected)
        val politeHits = occurrences(text, polite)
        val signals = occurrences(text, accepted)
            .filter { a -> a !in politeHits && rejectHits.none { it.covers(a) } && !negated(text, a) && !isBareDone(text, a) }
            .map { it.first } +
            text.indices.filter { i ->
                ((text[i] in fixWords && text[i] != DONE) || (evidence.fixCommitCited == true && isSha(text[i]))) && !negated(text, i..i)
            }
        return occurrences(text, rebuttals).any { m ->
            val selfNegating = text.subList(m.first, m.last + 1).any { it in negations || it in already }
            !negated(text, m, if (selfNegating) answers else emptySet()) && signals.none { it < m.first }
        }
    }

    private fun keywordVerdict(
        text: List<String>,
        evidence: ReplyEvidence,
    ): ReplyVerdict {
        val acceptHits = occurrences(text, accepted)
        val rejectHits = occurrences(text, rejected)
        // "не согласен" is a dismissal, not a negated "согласен": drop hits nested in a longer hit of the other list
        val reject = rejectHits.filter { r -> acceptHits.none { it.covers(r) } }
        val politeHits = if (reject.isEmpty()) emptyList() else occurrences(text, polite)
        val accept = acceptHits.filter { a -> rejectHits.none { it.covers(a) } && a !in politeHits }
        return when {
            (accept + reject).any { negated(text, it) } -> ReplyVerdict.UNKNOWN
            accept.isNotEmpty() && reject.isEmpty() -> acceptVerdict(text, accept, evidence)
            reject.isNotEmpty() && accept.isEmpty() -> ReplyVerdict.REJECTED
            accept.isEmpty() && neutralFixCommit(text, evidence) -> ReplyVerdict.ACCEPTED
            else -> ReplyVerdict.UNKNOWN
        }
    }

    /** Verdict of an accept-only reply; see the class KDoc. */
    private fun acceptVerdict(
        text: List<String>,
        accept: List<IntRange>,
        evidence: ReplyEvidence,
    ): ReplyVerdict =
        when {
            evidence.fixCommitCited == true && citesFix(text) -> ReplyVerdict.ACCEPTED
            doubtful(evidence.text, text, accept) -> ReplyVerdict.UNKNOWN
            else -> ReplyVerdict.ACCEPTED
        }

    /** An MR commit sha in a short reply with no keyword at all. */
    private fun neutralFixCommit(
        text: List<String>,
        evidence: ReplyEvidence,
    ): Boolean = evidence.fixCommitCited == true && evidence.text.length <= MAX_ACCEPTED_LENGTH && text.any(::isSha)

    /** Commit-sha-like words of [reply], lowercased (see [isSha]). */
    fun citedShas(reply: String): List<String> = words(reply).filter(::isSha)

    /** A [FIX_WORDS] word followed within [FIX_CITE_WINDOW] words by a commit sha. */
    private fun citesFix(text: List<String>): Boolean =
        text.indices.any { i ->
            text[i] in fixWords && text.subList(i + 1, (i + 1 + FIX_CITE_WINDOW).coerceAtMost(text.size)).any(::isSha)
        }

    /** A 7-40 char hex word with both a digit and a letter, so plain numbers and words do not count. */
    private fun isSha(word: String): Boolean =
        word.length in MIN_SHA..MAX_SHA && word.all { it in '0'..'9' || it in 'a'..'f' } && word.any { it.isDigit() } &&
            word.any { it.isLetter() }

    /** A `?` outside backtick code (`as?`, `?.` and `?:` in quoted code are no question). */
    private fun asksQuestion(reply: String): Boolean {
        val ticks = reply.runningFold(0) { n, c -> if (c == '`') n + 1 else n }
        return reply.indices.any { reply[it] == '?' && ticks[it] % 2 == 0 }
    }

    private fun negated(
        text: List<String>,
        hit: IntRange,
        ignored: Set<String> = emptySet(),
    ): Boolean = text.subList((hit.first - NEGATION_WINDOW).coerceAtLeast(0), hit.first).any { it in negations && it !in ignored }

    private fun isBareDone(
        text: List<String>,
        hit: IntRange,
    ): Boolean = hit.first == hit.last && text[hit.first] == DONE

    /** An accept-only reply that should not count as accepted; see the class KDoc. */
    private fun doubtful(
        reply: String,
        text: List<String>,
        accept: List<IntRange>,
    ): Boolean {
        val weakHits = occurrences(text, weak)
        val strong = accept.filter { a -> weakHits.none { it == a || it.covers(a) } }
        val nearAlready = strong.any { hit ->
            text.subList((hit.first - ALREADY_WINDOW).coerceAtLeast(0), (hit.last + ALREADY_WINDOW + 1).coerceAtMost(text.size)).any {
                it in already
            }
        }
        return strong.isEmpty() || reply.length > MAX_ACCEPTED_LENGTH || nearAlready
    }

    private fun IntRange.covers(other: IntRange): Boolean = this != other && first <= other.first && last >= other.last

    /** Word-index ranges of every keyword occurrence in [text]. */
    private fun occurrences(
        text: List<String>,
        keywords: List<List<String>>,
    ): List<IntRange> =
        keywords.flatMap { kw ->
            (0..text.size - kw.size)
                .filter { i -> kw.indices.all { text[i + it] == kw[it] } }
                .map { it until it + kw.size }
        }

    /** [reply] with each closed `"…"`, `“…”`, `«…»` or backtick span replaced by the commit shas in it. */
    private tailrec fun unquoted(
        reply: String,
        from: Int = 0,
        out: String = "",
    ): String {
        val open = reply.indexOfAny(quotes.keys.toCharArray(), from)
        val close = if (open < 0) -1 else reply.indexOf(quotes.getValue(reply[open]), open + 1)
        if (close < 0) return out + reply.substring(from)
        val shas = words(reply.substring(open + 1, close)).filter(::isSha).joinToString(" ")
        return unquoted(reply, close + 1, out + reply.substring(from, open) + " $shas ")
    }

    /** Lowercased words; apostrophes stay inside words (`won't`), `ё` folds to `е`. */
    private fun words(s: String): List<String> =
        s
            .lowercase()
            .replace('ё', 'е')
            .replace('’', '\'')
            .splitWhen { !it.isLetterOrDigit() && it != '\'' }
            .filter { it.isNotBlank() }

    private fun String.splitWhen(isSeparator: (Char) -> Boolean): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        for (c in this) {
            if (isSeparator(c)) {
                out += cur.toString()
                cur.clear()
            } else {
                cur.append(c)
            }
        }
        out += cur.toString()
        return out
    }
}
