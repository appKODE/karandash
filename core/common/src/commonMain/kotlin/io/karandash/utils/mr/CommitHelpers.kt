package io.karandash.utils.mr

import io.karandash.models.Commit
import io.karandash.models.MergeRequest
import io.karandash.utils.ConventionalTypes
import io.karandash.utils.toEscapedAlternation
import kotlinx.atomicfu.atomic

/**
 * Default length for abbreviated commit SHA hashes used in user-facing messages.
 */
const val SHORT_SHA_LENGTH: Int = 7

/**
 * Abbreviated-SHA length for finding text. The host sets it from `display.git.shortShaLength` at the start of
 * each run; rules read it through [shortSha]. Process-wide because rule modules get no run context in `validate`.
 */
object ShortSha {
    private val current = atomic(SHORT_SHA_LENGTH)

    /** Current abbreviated-SHA length. */
    val length: Int get() = current.value

    /** Sets the length used by [shortSha] (the host passes the already-clamped config value). */
    fun configure(length: Int) {
        current.value = length
    }
}

/** This SHA abbreviated to [ShortSha.length] characters. */
fun String.shortSha(): String = take(ShortSha.length)

// [conventionalCommitPattern] over the default types, written out so the regex stays a source literal.
private val CONVENTIONAL_COMMIT_REGEX = Regex("^(feat|fix|docs|style|refactor|perf|test|build|ci|chore|revert)(\\(.+\\))?!?: .+")

/**
 * The conventional-commit pattern `type(scope)!: subject` over [types], escaped for regex use.
 * the one shape the commit-messages rule, the title-format `@conventional` macro and [isConventional] share.
 */
fun conventionalCommitPattern(types: List<String> = ConventionalTypes.DEFAULT): String =
    "^(${types.toEscapedAlternation()})(\\(.+\\))?!?: .+"

internal fun conventionalCommitRegexPattern(): String = CONVENTIONAL_COMMIT_REGEX.pattern

/**
 * Extract all commit messages from the MR.
 */
fun MergeRequest.commitMessages(): List<String> = commits.map { it.message }

/**
 * Filter commits by author name (case-insensitive).
 */
fun MergeRequest.commitsByAuthor(authorName: String): List<Commit> = commits.filter { it.author.name.equals(authorName, ignoreCase = true) }

/**
 * Check if the MR has more commits than the threshold (squashable).
 */
fun MergeRequest.hasSquashableCommits(maxCommits: Int = 5): Boolean = commits.size > maxCommits

/**
 * Check if this commit is a fixup or squash commit.
 */
fun Commit.isFixup(): Boolean = message.startsWith("fixup!") || message.startsWith("squash!")

/**
 * Check if this commit follows conventional commit format: `type(scope)?: subject` or `type!: subject`
 * on its first line, as the commit-messages rule checks it.
 */
fun Commit.isConventional(): Boolean = CONVENTIONAL_COMMIT_REGEX.containsMatchIn(message)
