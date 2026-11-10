package io.karandash.plugins.ai.core

import kotlin.test.Test
import kotlin.test.assertEquals

class ReplyClassifierTest {
    private fun verdict(text: String) = ReplyClassifier.classify(text)

    private fun cited(text: String) = ReplyClassifier.classify(ReplyEvidence(text, fixCommitCited = true))

    @Test
    fun `dismissive reply labels rejected`() {
        assertEquals(ReplyVerdict.REJECTED, verdict("не баг"))
        assertEquals(ReplyVerdict.REJECTED, verdict("Это ложное срабатывание"))
        assertEquals(ReplyVerdict.REJECTED, verdict("false positive"))
        assertEquals(ReplyVerdict.REJECTED, verdict("This is intended, won't fix"))
    }

    @Test
    fun `accepting reply labels accepted`() {
        assertEquals(ReplyVerdict.ACCEPTED, verdict("исправил"))
        assertEquals(ReplyVerdict.ACCEPTED, verdict("Fixed, thanks"))
        assertEquals(ReplyVerdict.ACCEPTED, verdict("good catch!"))
    }

    @Test
    fun `neutral reply stays unknown`() {
        assertEquals(ReplyVerdict.UNKNOWN, verdict("let me look at the logs"))
        assertEquals(ReplyVerdict.UNKNOWN, verdict(""))
    }

    @Test
    fun `question stays unknown`() {
        assertEquals(ReplyVerdict.UNKNOWN, verdict("why?"))
        assertEquals(ReplyVerdict.UNKNOWN, verdict("fixed?"))
    }

    @Test
    fun `negated keyword stays unknown`() {
        assertEquals(ReplyVerdict.UNKNOWN, verdict("not fixed yet"))
        assertEquals(ReplyVerdict.UNKNOWN, verdict("не исправил"))
    }

    @Test
    fun `quoted bot text stays unknown`() {
        assertEquals(ReplyVerdict.UNKNOWN, verdict("> load() may return null\n\nfixed"))
    }

    @Test
    fun `mixed positive and negative stays unknown`() {
        assertEquals(ReplyVerdict.UNKNOWN, verdict("fixed one, the other is a false positive"))
    }

    @Test
    fun `long reply with only accept keywords stays unknown`() {
        val long = "Thanks for the note. " + "The cache is rebuilt on every start and the old entries are dropped by the loader. ".repeat(4)
        assertEquals(ReplyVerdict.UNKNOWN, verdict(long))
        assertEquals(ReplyVerdict.REJECTED, verdict("This is intended. " + "The loader drops stale entries on start. ".repeat(8)))
        assertEquals(ReplyVerdict.ACCEPTED, verdict("Thanks for the note, fixed the loader to drop stale entries on start"))
    }

    @Test
    fun `already next to an accept keyword stays unknown`() {
        assertEquals(ReplyVerdict.UNKNOWN, verdict("already fixed in the parent branch"))
        assertEquals(ReplyVerdict.UNKNOWN, verdict("this was done already"))
        assertEquals(ReplyVerdict.UNKNOWN, verdict("Уже исправлено выше"))
        assertEquals(ReplyVerdict.ACCEPTED, verdict("fixed in the parent branch"))
        assertEquals(ReplyVerdict.ACCEPTED, verdict("Исправлено выше"))
    }

    @Test
    fun `invalid or false marker rejects even next to thanks`() {
        assertEquals(ReplyVerdict.REJECTED, verdict("Thanks for the review but the warning is not valid here"))
        assertEquals(ReplyVerdict.REJECTED, verdict("Спасибо, но замечание ложное"))
        assertEquals(ReplyVerdict.REJECTED, verdict("Невалидно, спасибо"))
        assertEquals(ReplyVerdict.UNKNOWN, verdict("It is not intentional, will look"))
        assertEquals(ReplyVerdict.REJECTED, verdict("invalid"))
        assertEquals(ReplyVerdict.REJECTED, verdict("Ложное срабатывание"))
    }

    @Test
    fun `weak accept keywords alone stay unknown`() {
        assertEquals(ReplyVerdict.UNKNOWN, verdict("agree"))
        assertEquals(ReplyVerdict.UNKNOWN, verdict("addressed"))
        assertEquals(ReplyVerdict.UNKNOWN, verdict("resolved"))
        assertEquals(ReplyVerdict.UNKNOWN, verdict("Всё верно"))
        assertEquals(ReplyVerdict.UNKNOWN, verdict("все верно"))
        assertEquals(ReplyVerdict.ACCEPTED, verdict("Agree, fixed"))
        assertEquals(ReplyVerdict.ACCEPTED, verdict("Все верно, поправил"))
        assertEquals(ReplyVerdict.UNKNOWN, verdict("верно"))
        assertEquals(ReplyVerdict.UNKNOWN, verdict("тоже верно"))
        assertEquals(ReplyVerdict.UNKNOWN, verdict("Они все валидно обработаются"))
        assertEquals(ReplyVerdict.UNKNOWN, verdict("agree it is a false positive"))
    }

    @Test
    fun `rebuttal with an accept word is rejected`() {
        assertEquals(ReplyVerdict.REJECTED, verdict("Not valid, the null check is already there. Thanks"))
        assertEquals(ReplyVerdict.REJECTED, verdict("Already addressed: the caller checks the result first"))
        assertEquals(ReplyVerdict.REJECTED, verdict("Both checks pass - no change needed, done"))
        assertEquals(ReplyVerdict.REJECTED, verdict("It is intentionally written this way, thanks"))
        assertEquals(ReplyVerdict.REJECTED, verdict("Minor, not blocking. Thanks for the note"))
        assertEquals(ReplyVerdict.REJECTED, verdict("Works as intended, resolved"))
        assertEquals(ReplyVerdict.REJECTED, verdict("Traced it: not a race, both calls run on the main thread"))
        assertEquals(ReplyVerdict.REJECTED, verdict("Duplicate of the finding answered above, thanks"))
        assertEquals(ReplyVerdict.REJECTED, verdict("В коде всё верно"))
        assertEquals(ReplyVerdict.REJECTED, verdict("Технически верно, но столько элементов в списке не бывает"))
        assertEquals(ReplyVerdict.REJECTED, verdict("Проверка уже есть выше, спасибо"))
        assertEquals(ReplyVerdict.REJECTED, verdict("Это не блокирует, поправим потом"))
    }

    @Test
    fun `genuine fix ack next to a rebuttal pair stays accepted`() {
        assertEquals(ReplyVerdict.ACCEPTED, verdict("Fixed, thanks"))
        assertEquals(ReplyVerdict.ACCEPTED, verdict("Поправил"))
        assertEquals(ReplyVerdict.ACCEPTED, verdict("Валидно, поправил"))
        assertEquals(ReplyVerdict.ACCEPTED, verdict("По делу, исправлю"))
    }

    @Test
    fun `fix ack citing a commit sha is accepted even when long`() {
        val details = " Теперь загрузка профиля и подписки хранит ошибки отдельно, и каждый запрос меняет только свой флаг.".repeat(4)
        assertEquals(ReplyVerdict.ACCEPTED, cited("Исправлено в 1a2b3c4d:$details"))
        assertEquals(ReplyVerdict.ACCEPTED, cited("Fixed in 9f8e7d6c, the flag is now cleared at the start.$details"))
        assertEquals(ReplyVerdict.UNKNOWN, verdict("Исправлено:$details"))
        assertEquals(ReplyVerdict.UNKNOWN, verdict("Checked at head 1a2b3c4d and the flag is cleared, thanks.$details"))
    }

    @Test
    fun `cited sha outside the MR is not a fix`() {
        val long = "Fixed in 1a2b3c4d." + " The cache is rebuilt on start and stale entries are dropped by the loader.".repeat(4)
        assertEquals(ReplyVerdict.UNKNOWN, ReplyClassifier.classify(ReplyEvidence(long, fixCommitCited = false)))
        assertEquals(ReplyVerdict.ACCEPTED, ReplyClassifier.classify(ReplyEvidence(long, fixCommitCited = true)))
    }

    @Test
    fun `a sha with no known commit list does not make a long fix ack accept`() {
        val long = "Fixed in 1a2b3c4d." + " The cache is rebuilt on start and stale entries are dropped by the loader.".repeat(4)
        assertEquals(ReplyVerdict.UNKNOWN, ReplyClassifier.classify(ReplyEvidence(long)))
        assertEquals(ReplyVerdict.ACCEPTED, ReplyClassifier.classify(ReplyEvidence("Fixed in 1a2b3c4d")))
    }

    @Test
    fun `cited fix commit with neutral wording is accepted`() {
        assertEquals(ReplyVerdict.ACCEPTED, ReplyClassifier.classify(ReplyEvidence("Добавлен clamp в 1a2b3c4d", fixCommitCited = true)))
        assertEquals(ReplyVerdict.UNKNOWN, ReplyClassifier.classify(ReplyEvidence("Добавлен clamp в 1a2b3c4d")))
        assertEquals(ReplyVerdict.REJECTED, ReplyClassifier.classify(ReplyEvidence("Not valid at 1a2b3c4d", fixCommitCited = true)))
    }

    @Test
    fun `reaction against the text keeps the reply unknown`() {
        assertEquals(ReplyVerdict.UNKNOWN, ReplyClassifier.classify(ReplyEvidence("fixed", reaction = ReplyReaction.DOWN)))
        assertEquals(ReplyVerdict.UNKNOWN, ReplyClassifier.classify(ReplyEvidence("false positive", reaction = ReplyReaction.UP)))
        assertEquals(ReplyVerdict.ACCEPTED, ReplyClassifier.classify(ReplyEvidence("fixed", reaction = ReplyReaction.UP)))
        assertEquals(ReplyVerdict.REJECTED, ReplyClassifier.classify(ReplyEvidence("false positive", reaction = ReplyReaction.DOWN)))
    }

    @Test
    fun `question mark inside backtick code is no question`() {
        assertEquals(ReplyVerdict.REJECTED, verdict("Ложное срабатывание: fallback уже стоит в `state.user as? User ?: Guest`"))
        assertEquals(ReplyVerdict.REJECTED, verdict("Не применяю: `user?.name` уже проверен выше"))
        assertEquals(ReplyVerdict.UNKNOWN, verdict("Ложное срабатывание? `user?.name` проверен выше"))
        assertEquals(ReplyVerdict.UNKNOWN, verdict("fixed in `a.b`?"))
    }

    @Test
    fun `out of scope and superseded replies are rejected`() {
        assertEquals(ReplyVerdict.REJECTED, verdict("Не относится к текущим изменениям"))
        assertEquals(ReplyVerdict.REJECTED, verdict("Намеренный плейсхолдер для dev сборки"))
        assertEquals(ReplyVerdict.REJECTED, verdict("Superseded - no longer applicable after the rewrite"))
        assertEquals(ReplyVerdict.REJECTED, verdict("Fallback уже присутствует на следующей строке"))
        assertEquals(ReplyVerdict.UNKNOWN, verdict("Это относится к другому MR"))
    }

    @Test
    fun `a fix or accept word before a rebuttal marker keeps the reply from rejecting`() {
        assertEquals(ReplyVerdict.UNKNOWN, verdict("Fixed the invalid input handling"))
        assertEquals(ReplyVerdict.ACCEPTED, verdict("Good catch, added a test so it's not a race anymore"))
        assertEquals(ReplyVerdict.UNKNOWN, verdict("Made the cast intentionally explicit, fixed"))
        assertEquals(ReplyVerdict.UNKNOWN, verdict("Поправил, специально вынес проверку"))
        assertEquals(ReplyVerdict.REJECTED, ReplyClassifier.classify(ReplyEvidence("Not valid at 1a2b3c4d", fixCommitCited = true)))
        assertEquals(ReplyVerdict.REJECTED, verdict("Both checks pass - no change needed, done"))
    }

    @Test
    fun `bare done before a dismissive marker does not block the rejection`() {
        assertEquals(ReplyVerdict.REJECTED, verdict("Done. This is a duplicate of the other finding"))
        assertEquals(ReplyVerdict.REJECTED, verdict("Done, was a duplicate of X, merged"))
        assertEquals(ReplyVerdict.ACCEPTED, verdict("Fixed, was a duplicate of X, merged"))
        assertEquals(ReplyVerdict.ACCEPTED, verdict("Good catch, this is a duplicate of the other finding"))
        assertEquals(
            ReplyVerdict.REJECTED,
            ReplyClassifier.classify(ReplyEvidence("Done, duplicate of X", fixCommitCited = false)),
        )
        assertEquals(
            ReplyVerdict.ACCEPTED,
            ReplyClassifier.classify(ReplyEvidence("Done in 1a2b3c4, duplicate of X", fixCommitCited = true)),
        )
    }

    @Test
    fun `thanks next to a dismissal rejects`() {
        assertEquals(ReplyVerdict.REJECTED, verdict("Thanks, false positive"))
        assertEquals(ReplyVerdict.REJECTED, verdict("Спасибо, это специально"))
        assertEquals(ReplyVerdict.UNKNOWN, verdict("Fixed one, the other is intentional"))
    }

    @Test
    fun `a negation before a marker that already negates still rejects`() {
        assertEquals(ReplyVerdict.REJECTED, verdict("No, already there"))
        assertEquals(ReplyVerdict.REJECTED, verdict("Нет, уже есть проверка"))
        assertEquals(ReplyVerdict.REJECTED, verdict("No, no change needed"))
        assertEquals(ReplyVerdict.UNKNOWN, verdict("It is not intentional, will look"))
        assertEquals(ReplyVerdict.UNKNOWN, verdict("Не так задумано, посмотрю"))
    }

    @Test
    fun `a negation before an already marker still applies`() {
        assertEquals(ReplyVerdict.UNKNOWN, verdict("Not already handled, will look"))
        assertEquals(ReplyVerdict.UNKNOWN, verdict("It is not already there"))
        assertEquals(ReplyVerdict.UNKNOWN, verdict("Это не уже есть, посмотрю"))
        assertEquals(ReplyVerdict.REJECTED, verdict("Already handled in the caller"))
        assertEquals(ReplyVerdict.REJECTED, verdict("No, already handled in the caller"))
    }

    @Test
    fun `keywords inside double quotes or inline code do not count`() {
        assertEquals(ReplyVerdict.ACCEPTED, verdict("Fixed, the `ignore` flag is gone"))
        assertEquals(ReplyVerdict.ACCEPTED, verdict("Fixed the \"not a bug\" comment"))
        assertEquals(ReplyVerdict.ACCEPTED, verdict("Поправил «ложное» сообщение"))
        assertEquals(ReplyVerdict.REJECTED, verdict("False positive, \"fixed\" here is the old loader"))
        assertEquals(ReplyVerdict.UNKNOWN, verdict("Fixed, the ignore flag is gone"))
        assertEquals(ReplyVerdict.UNKNOWN, verdict("Fixed, the \"ignore flag is gone"))
    }

    @Test
    fun `added in reply to the finding is accepted`() {
        assertEquals(ReplyVerdict.ACCEPTED, verdict("Верно, мой недосмотр. Добавил ключ в LaunchedEffect"))
        val details = " Теперь повторный старт ждёт завершения предыдущего запуска и не дублирует отправку кода.".repeat(4)
        assertEquals(ReplyVerdict.ACCEPTED, cited("Исправлено общим владельцем `ResendJob` в `1a2b3c4d`.$details"))
        assertEquals(ReplyVerdict.UNKNOWN, verdict("Исправлено общим владельцем ResendJob и в других местах тоже, см 1a2b3c4d.$details"))
    }
}
