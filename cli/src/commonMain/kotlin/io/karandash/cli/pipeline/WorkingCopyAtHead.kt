package io.karandash.cli.pipeline

import io.karandash.api.run.RepositoryFileReader
import io.karandash.models.ChangeType
import io.karandash.models.MergeRequest
import io.karandash.platform.PathSecurity
import io.karandash.platform.exists
import io.karandash.platform.isFile
import io.karandash.platform.readText
import io.karandash.sdk.Logger
import kotlinx.io.IOException
import kotlinx.io.files.Path

/**
 * The working copy (cwd) when it is a checkout of the MR head, as on a CI runner or a review server: head files are
 * read from disk, and a file missing on disk is missing at the head, so neither costs a platform call.
 *
 * A git checkout matches when its `HEAD` is `diffRefs.headSha`. Without a readable `.git` (an exported tree), every
 * hunk's new side (context and added lines) must appear in its file line for line, every deleted file be gone, and
 * at least one change not be a deletion.
 * limit: the no-git check only sees the changed files; an exported tree of a later commit that keeps every hunk passes.
 */
internal object WorkingCopyAtHead {
    private val HUNK_HEADER = Regex("""(?m)^@@.*$""")

    fun matches(
        mr: MergeRequest,
        gitHead: String? = gitHead(),
    ): Boolean {
        val headSha = mr.diffRefs?.headSha
        if (gitHead != null && headSha != null) return gitHead.equals(headSha, ignoreCase = true)
        val checked = mr.changes.filter { it.diffContent != null }
        return checked.any { it.changeType != ChangeType.DELETED } &&
            checked.all { change ->
                val text = read(change.path)
                if (change.changeType == ChangeType.DELETED) {
                    text == null
                } else {
                    val file = "\n" + (text ?: return@all false).lineSequence().joinToString("\n") { it.trimEnd('\r') } + "\n"
                    newSides(change.diffContent.orEmpty()).all { "\n$it\n" in file }
                }
            }
    }

    /** The commit the working copy's `HEAD` points at, or `null` when cwd is not the root of a readable git checkout. */
    fun gitHead(): String? {
        val dotGit = read(".git")
        val gitDir = if (dotGit == null) ".git" else dotGit.trim().removePrefix("gitdir:").trim()
        val common = readAbsolute("$gitDir/commondir")?.trim()?.let { if (it.startsWith("/")) it else "$gitDir/$it" } ?: gitDir
        val head = readAbsolute("$gitDir/HEAD")?.trim() ?: return null
        val ref = head.removePrefix("ref:").trim().takeIf { head.startsWith("ref:") } ?: return head
        return (readAbsolute("$gitDir/$ref") ?: readAbsolute("$common/$ref"))?.trim()
            ?: readAbsolute("$common/packed-refs")
                ?.lineSequence()
                ?.firstOrNull { it.endsWith(" $ref") }
                ?.substringBefore(' ')
    }

    /** A repository-relative file's text; `null` when missing, unreadable or outside the repository. */
    fun read(path: String): String? {
        if (!isRepositoryPath(path)) return null
        return try {
            Path(path).takeIf { it.exists() && it.isFile() }?.readText()
        } catch (_: IOException) {
            null
        }
    }

    /** A relative path that stays inside the repository (no leading `/`, no `..` escape). */
    fun isRepositoryPath(path: String): Boolean = !path.startsWith("/") && PathSecurity.normalizePath(path) == "/$path"

    /** Git metadata is read wherever `.git` points (a worktree's `gitdir:` is absolute), not only inside the repository. */
    private fun readAbsolute(path: String): String? =
        try {
            Path(path).takeIf { it.exists() && it.isFile() }?.readText()
        } catch (_: IOException) {
            null
        }

    /** Each hunk's new side: its context and added lines, in order, joined by `\n`; pure-deletion hunks are skipped. */
    private fun newSides(diff: String): List<String> =
        diff
            .split(HUNK_HEADER)
            .drop(1)
            .map { hunk ->
                hunk
                    .removePrefix("\n")
                    .lineSequence()
                    .filterNot { it.startsWith("-") || it.startsWith("\\") }
                    .map { it.drop(1).trimEnd('\r') }
                    .toList()
                    .dropLastWhile { it.isEmpty() }
                    .joinToString("\n")
            }.filter { it.isNotEmpty() }
}

/**
 * [delegate], except that the MR head is read from disk when the working copy is [WorkingCopyAtHead]; logs which
 * one, so a checkout left on another commit shows up instead of silently feeding the review old code.
 */
internal fun withWorkingCopy(
    mr: MergeRequest,
    logger: Logger,
    gitHead: String? = WorkingCopyAtHead.gitHead(),
    delegate: RepositoryFileReader,
): RepositoryFileReader {
    val head = mr.diffRefs?.headSha ?: mr.sourceBranch
    if (!WorkingCopyAtHead.matches(mr, gitHead)) {
        val at = gitHead?.let { "git HEAD is $it" } ?: "changed files differ from the diff"
        logger.info("Working copy is not at the MR head $head ($at): head files are not read from disk")
        return delegate
    }
    logger.info("Working copy is at the MR head $head: repository files at the head are read from disk")
    return RepositoryFileReader { ref, path -> if (ref == head) WorkingCopyAtHead.read(path) else delegate.read(ref, path) }
}
