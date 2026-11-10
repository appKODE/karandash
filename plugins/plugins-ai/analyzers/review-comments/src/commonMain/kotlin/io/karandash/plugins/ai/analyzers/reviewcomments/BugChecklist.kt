package io.karandash.plugins.ai.analyzers.reviewcomments

import io.karandash.models.FileChange
import io.karandash.plugins.ai.core.LanguageDetector

/**
 * `ai.review.bugChecklist`, on by default): the bug classes reviewers most often miss, appended to the default
 * review prompt, plus one line for the MR's main language ([LanguageDetector]).
 */
internal object BugChecklist {
    private const val JS_LINE: String =
        "- JavaScript: a Promise never awaited; == instead of ===; new Date() parsed in local time; [].every() is true on empty"

    private val GENERIC = """
Bug checklist: check each changed line against these before answering:
- all {} / any {} / none {} / every() on a collection that can be empty
- current time without an explicit zone, or the device time zone where a fixed one is needed
- equality that can never match (different types, boxed or reference comparison, wrong case or units)
- async work that is not awaited, or a blocking call that should suspend
- secrets, keys or tokens in client code or logs
- a side effect moved across a condition or out of a block (now runs in more or fewer cases)
- off-by-one in ranges, indices, sizes or loop bounds
    """.trimIndent()

    private val BY_LANGUAGE = mapOf(
        "kotlin" to
            "- Kotlin: LocalDateTime.now() or Clock.System without a TimeZone; == between unrelated types; " +
            "a launch {} whose Job is dropped; runCatching swallowing CancellationException",
        "java" to "- Java: == on String or boxed Integer; LocalDateTime.now() without a ZoneId; a CompletableFuture never joined",
        "swift" to
            "- Swift: Date() or Calendar.current where a fixed time zone is needed; a Task {} never awaited; " +
            "a force unwrap of optional input",
        "cpp" to "- C++: signed/unsigned comparison; an iterator or reference used after the container changed; a missing break",
        "c" to "- C: signed/unsigned comparison; a buffer one byte short for the terminating NUL; a missing break in switch",
        "typescript" to JS_LINE.replace("JavaScript", "TypeScript"),
        "javascript" to JS_LINE,
    )

    /** The checklist for [changes]; the language line is left out when no listed language dominates. */
    fun forChanges(changes: List<FileChange>): String {
        val language = LanguageDetector.detect(changes)?.let { BY_LANGUAGE[it] }
        return if (language == null) GENERIC else "$GENERIC\n$language"
    }
}
