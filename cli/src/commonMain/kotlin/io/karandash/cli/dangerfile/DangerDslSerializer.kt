package io.karandash.cli.dangerfile

import io.karandash.js.JsUtils
import io.karandash.models.ChangeType
import io.karandash.models.MergeRequest

/**
 * Serializes a karandash [MergeRequest] into the DangerDSL JSON format
 * expected by danger-kotlin and the Node.js bridge script.
 *
 * The output contains `git`, `github`, and `gitlab` blocks so that Dangerfiles
 * written for either platform can access the data they expect.
 */
object DangerDslSerializer {
    private const val KEY_TITLE = "\"title\":"
    private const val KEY_STATE = ",\"state\":"
    private const val KEY_DRAFT = ",\"draft\":"
    private const val KEY_IID = ",\"iid\":"
    private const val KEY_DESCRIPTION = ",\"description\":"
    private const val KEY_COMMITS_OPEN = "},\"commits\":["
    private const val KEY_SHA = "{\"sha\":"
    private const val KEY_MESSAGE = ",\"message\":"
    private const val KEY_AUTHOR_NAME = ",\"authorName\":"
    private const val KEY_NAME = "{\"name\":"
    private const val KEY_LOGIN = ",\"login\":"
    private const val KEY_LABELS_OPEN = ",\"labels\":["
    private const val KEY_AUTHOR_NAME_OBJ = ",\"author\":{\"name\":"

    /**
     * Serialize a [MergeRequest] to DangerDSL JSON.
     *
     * @param mr The merge request data from karandash
     * @return DangerDSL-compatible JSON string
     */
    fun serialize(mr: MergeRequest): String =
        buildString {
            append('{')

            // git block
            append("\"git\":{")
            appendFileArrays(mr)
            append(',')
            appendCommits(mr)
            append('}')

            // github block
            append(",\"github\":{\"pr\":{")
            append(KEY_TITLE).append(escapeJsonString(mr.title))
            append(",\"body\":").append(escapeJsonString(mr.description ?: ""))
            append(",\"number\":").append(mr.iid)
            append(KEY_STATE).append(escapeJsonString(mr.state.value))
            append(KEY_DRAFT).append(mr.draft)
            append(",\"user\":{\"login\":").append(escapeJsonString(mr.author.username)).append('}')
            append(",\"head\":{\"ref\":").append(escapeJsonString(mr.sourceBranch)).append('}')
            append(",\"base\":{\"ref\":").append(escapeJsonString(mr.targetBranch)).append('}')
            appendLabelsAsObjects(mr)
            appendRequestedReviewers(mr)
            append(KEY_COMMITS_OPEN)
            appendGithubCommits(mr)
            append("]}")

            // gitlab block
            append(",\"gitlab\":{\"mr\":{")
            append(KEY_TITLE).append(escapeJsonString(mr.title))
            append(KEY_DESCRIPTION).append(escapeJsonString(mr.description ?: ""))
            append(KEY_IID).append(mr.iid)
            append(KEY_STATE).append(escapeJsonString(mr.state.value))
            append(KEY_DRAFT).append(mr.draft)
            appendLabelsAsArray(mr)
            append(",\"source_branch\":").append(escapeJsonString(mr.sourceBranch))
            append(",\"target_branch\":").append(escapeJsonString(mr.targetBranch))
            append(KEY_AUTHOR_NAME_OBJ).append(escapeJsonString(mr.author.name))
            append(",\"username\":").append(escapeJsonString(mr.author.username)).append('}')
            append(KEY_COMMITS_OPEN)
            appendGitlabCommits(mr)
            append("]}")

            // Flat fields for the bridge script (it reads these directly from the DSL root)
            append(",\"title\":").append(escapeJsonString(mr.title))
            append(KEY_DESCRIPTION).append(escapeJsonString(mr.description ?: ""))
            append(KEY_IID).append(mr.iid)
            append(KEY_STATE).append(escapeJsonString(mr.state.value))
            append(KEY_DRAFT).append(mr.draft)
            append(",\"sourceBranch\":").append(escapeJsonString(mr.sourceBranch))
            append(",\"targetBranch\":").append(escapeJsonString(mr.targetBranch))
            append(KEY_AUTHOR_NAME).append(escapeJsonString(mr.author.name))
            append(",\"authorLogin\":").append(escapeJsonString(mr.author.username))
            appendLabelsAsArray(mr)
            append(",\"reviewers\":[")
            mr.reviewers.forEachIndexed { i, r ->
                if (i > 0) append(',')
                append(KEY_NAME).append(escapeJsonString(r.name))
                append(KEY_LOGIN).append(escapeJsonString(r.username)).append('}')
            }
            append(']')

            // changes and commits arrays for bridge script
            append(",\"changes\":[")
            mr.changes.forEachIndexed { i, fc ->
                if (i > 0) append(',')
                append("{\"path\":").append(escapeJsonString(fc.path))
                append(",\"changeType\":").append(escapeJsonString(fc.changeType.name))
                append('}')
            }
            append(']')
            append(",\"commits\":[")
            mr.commits.forEachIndexed { i, c ->
                if (i > 0) append(',')
                append(KEY_SHA).append(escapeJsonString(c.sha))
                append(KEY_MESSAGE).append(escapeJsonString(c.message))
                append(KEY_AUTHOR_NAME).append(escapeJsonString(c.author.name))
                append(",\"authorEmail\":").append(escapeJsonString(c.author.email))
                append('}')
            }
            append(']')

            append('}')
        }

    /** [s] as a quoted JSON string literal (the shared [JsUtils.quoteJson] escaper). */
    internal fun escapeJsonString(s: String): String = JsUtils.quoteJson(s)

    private fun StringBuilder.appendFileArrays(mr: MergeRequest) {
        val modified = mr.changes.filter { it.changeType == ChangeType.MODIFIED }.map { it.path }
        val created = mr.changes.filter { it.changeType == ChangeType.ADDED }.map { it.path }
        val deleted = mr.changes.filter { it.changeType == ChangeType.DELETED }.map { it.path }
        val renamed = mr.changes.filter { it.changeType == ChangeType.RENAMED }.map { it.path }

        append("\"modified_files\":[")
        modified.forEachIndexed { i, p ->
            if (i > 0) append(',')
            append(escapeJsonString(p))
        }
        append("],\"created_files\":[")
        created.forEachIndexed { i, p ->
            if (i > 0) append(',')
            append(escapeJsonString(p))
        }
        append("],\"deleted_files\":[")
        deleted.forEachIndexed { i, p ->
            if (i > 0) append(',')
            append(escapeJsonString(p))
        }
        append("],\"renamed_files\":[")
        renamed.forEachIndexed { i, p ->
            if (i > 0) append(',')
            append(escapeJsonString(p))
        }
        append(']')
    }

    private fun StringBuilder.appendCommits(mr: MergeRequest) {
        append("\"commits\":[")
        mr.commits.forEachIndexed { i, c ->
            if (i > 0) append(',')
            append(KEY_SHA).append(escapeJsonString(c.sha))
            append(KEY_MESSAGE).append(escapeJsonString(c.message))
            append(KEY_AUTHOR_NAME_OBJ).append(escapeJsonString(c.author.name))
            append(",\"email\":").append(escapeJsonString(c.author.email))
            append("}}")
        }
        append(']')
    }

    private fun StringBuilder.appendGithubCommits(mr: MergeRequest) {
        mr.commits.forEachIndexed { i, c ->
            if (i > 0) append(',')
            append(KEY_SHA).append(escapeJsonString(c.sha))
            append(",\"commit\":{\"message\":").append(escapeJsonString(c.message))
            append(KEY_AUTHOR_NAME_OBJ).append(escapeJsonString(c.author.name))
            append(",\"email\":").append(escapeJsonString(c.author.email))
            append("}}}")
        }
    }

    private fun StringBuilder.appendGitlabCommits(mr: MergeRequest) {
        mr.commits.forEachIndexed { i, c ->
            if (i > 0) append(',')
            append("{\"id\":").append(escapeJsonString(c.sha))
            append(KEY_MESSAGE).append(escapeJsonString(c.message))
            append(",\"author_name\":").append(escapeJsonString(c.author.name))
            append(",\"author_email\":").append(escapeJsonString(c.author.email))
            append('}')
        }
    }

    private fun StringBuilder.appendLabelsAsObjects(mr: MergeRequest) {
        append(KEY_LABELS_OPEN)
        mr.labels.forEachIndexed { i, label ->
            if (i > 0) append(',')
            append(KEY_NAME).append(escapeJsonString(label)).append('}')
        }
        append(']')
    }

    private fun StringBuilder.appendRequestedReviewers(mr: MergeRequest) {
        append(",\"requested_reviewers\":[")
        mr.reviewers.forEachIndexed { i, r ->
            if (i > 0) append(',')
            append("{\"login\":").append(escapeJsonString(r.username)).append('}')
        }
        append(']')
    }

    private fun StringBuilder.appendLabelsAsArray(mr: MergeRequest) {
        append(KEY_LABELS_OPEN)
        mr.labels.forEachIndexed { i, label ->
            if (i > 0) append(',')
            append(escapeJsonString(label))
        }
        append(']')
    }
}
