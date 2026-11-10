package io.karandash.platforms.github

import io.karandash.api.finding.ReviewNote
import io.karandash.api.finding.ReviewThread
import io.karandash.api.finding.ReviewThreadPosition
import io.karandash.models.support.KarandashJson
import io.karandash.pagination.PaginationResult
import io.karandash.pagination.paginateByCursor
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Review threads of a pull request through GraphQL (`reviewThreads`, `resolveReviewThread`,
 * `addPullRequestReviewThreadReply`): REST has no thread object. A thread id is its GraphQL node id; a note id is
 * the comment's REST id (`fullDatabaseId`), which REST `PATCH /pulls/comments/{id}` edits.
 */
internal class GitHubReviewThreads(
    private val client: GitHubClient,
) {
    /**
     * Every review thread with its first and last [MAX_COMMENTS] comments, following the thread cursor up to the run's
     * `http.pagination.maxPages` (more marks the pagination probe). The last page keeps a late bot reply of a long thread.
     */
    suspend fun list(
        owner: String,
        repo: String,
        prNumber: Int,
    ): List<ReviewThread> =
        paginateByCursor { cursor ->
            val variables = buildJsonObject {
                put("owner", owner)
                put("repo", repo)
                put("number", prNumber)
                put("after", cursor)
            }
            val data = KarandashJson.Default.decodeFromJsonElement(
                GhThreadsData.serializer(),
                client.graphql("reviewThreads", LIST_QUERY, variables),
            )
            val page = data.repository?.pullRequest?.reviewThreads
            PaginationResult(
                page?.nodes.orEmpty().map { it.toReviewThread() },
                page?.pageInfo?.endCursor?.takeIf { page.pageInfo.hasNextPage },
            )
        }

    suspend fun resolve(threadId: String) {
        client.graphql(
            "resolveReviewThread",
            "mutation(\$id: ID!) { resolveReviewThread(input: {threadId: \$id}) { thread { id } } }",
            buildJsonObject { put("id", threadId) },
        )
    }

    suspend fun reply(
        threadId: String,
        body: String,
    ) {
        client.graphql(
            "addPullRequestReviewThreadReply",
            "mutation(\$id: ID!, \$body: String!) { addPullRequestReviewThreadReply(" +
                "input: {pullRequestReviewThreadId: \$id, body: \$body}) { comment { id } } }",
            buildJsonObject {
                put("id", threadId)
                put("body", body)
            },
        )
    }

    private fun GhThread.toReviewThread(): ReviewThread =
        ReviewThread(
            id = id,
            resolved = isResolved,
            position = path?.let { ReviewThreadPosition(newPath = it, oldPath = it, newLine = line ?: originalLine) },
            notes = (comments.nodes + latest.nodes).distinctBy { it.fullDatabaseId ?: it.body }.map {
                ReviewNote(
                    id = it.fullDatabaseId.orEmpty(),
                    body = it.body,
                    author = it.author?.login,
                    createdAt = it.createdAt,
                    resolvable = true,
                    resolved = isResolved,
                    resolvedBy = resolvedBy?.login,
                )
            },
        )

    private companion object {
        const val MAX_COMMENTS = 100
        const val COMMENT_NODES = "nodes { fullDatabaseId body createdAt author { login } }"
        val LIST_QUERY =
            """
            query(${'$'}owner: String!, ${'$'}repo: String!, ${'$'}number: Int!, ${'$'}after: String) {
              repository(owner: ${'$'}owner, name: ${'$'}repo) {
                pullRequest(number: ${'$'}number) {
                  reviewThreads(first: 100, after: ${'$'}after) {
                    pageInfo { hasNextPage endCursor }
                    nodes {
                      id isResolved path line originalLine resolvedBy { login }
                      comments(first: $MAX_COMMENTS) { $COMMENT_NODES }
                      latest: comments(last: $MAX_COMMENTS) { $COMMENT_NODES }
                    }
                  }
                }
              }
            }
            """.trimIndent()
    }
}

@Serializable
internal data class GhThreadsData(
    val repository: GhRepository? = null,
)

@Serializable
internal data class GhRepository(
    val pullRequest: GhPullRequestThreads? = null,
)

@Serializable
internal data class GhPullRequestThreads(
    val reviewThreads: GhThreadPage,
)

@Serializable
internal data class GhThreadPage(
    val nodes: List<GhThread> = emptyList(),
    val pageInfo: GhPageInfo = GhPageInfo(),
)

@Serializable
internal data class GhPageInfo(
    val hasNextPage: Boolean = false,
    val endCursor: String? = null,
)

@Serializable
internal data class GhThread(
    val id: String,
    val isResolved: Boolean = false,
    val path: String? = null,
    val line: Int? = null,
    val originalLine: Int? = null,
    val resolvedBy: GhActor? = null,
    val comments: GhCommentPage = GhCommentPage(),
    val latest: GhCommentPage = GhCommentPage(),
)

@Serializable
internal data class GhCommentPage(
    val nodes: List<GhThreadComment> = emptyList(),
)

@Serializable
internal data class GhThreadComment(
    val fullDatabaseId: String? = null,
    val body: String = "",
    val createdAt: String? = null,
    val author: GhActor? = null,
)

@Serializable
internal data class GhActor(
    val login: String,
)
