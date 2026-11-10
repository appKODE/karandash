@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.platforms.awscodecommit

import io.karandash.providers.common.SupersededInlineComment
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** CodeCommit cannot resolve threads: an earlier run's inline comments are edited in place, never deleted. */
class AwsInlineCleanupTest {
    private val writes = mutableListOf<String>()

    private suspend fun service(): AwsCodeCommitPlatformService {
        val superseded = "${SupersededInlineComment.NOTE}\\n\\nolder <!-- kd -->"
        val engine = MockEngine { request ->
            val target = request.headers["X-Amz-Target"].orEmpty().substringAfter('.')
            val body = when (target) {
                "GetPullRequest" -> {
                    """{"pullRequest":{"pullRequestId":"7","title":"t","pullRequestStatus":"OPEN","authorArn":"a",""" +
                        """"creationDate":"0","pullRequestTargets":[{"repositoryName":"repo","sourceReference":"s",""" +
                        """"destinationReference":"d","destinationCommit":"b","sourceCommit":"h"}]}}"""
                }

                "GetCommentsForPullRequest" -> {
                    """{"commentsForPullRequestData":[""" +
                        """{"comments":[{"commentId":"s1","content":"<!-- sum --> summary"},""" +
                        """{"commentId":"s2","content":"<!-- sum --> again"},""" +
                        """{"commentId":"s3","content":"> quoting <!-- sum --> in a human reply"}]},""" +
                        """{"location":${location("a.kt")},"comments":[{"commentId":"c1","content":"old <!-- kd -->"},""" +
                        """{"commentId":"c2","content":"human"},{"commentId":"c4","content":"$superseded"}]},""" +
                        """{"location":${location("b.kt")},"comments":[{"commentId":"c3","content":"cached <!-- kd -->"}]}]}"""
                }

                else -> {
                    writes += "$target ${(request.body as TextContent).text}"
                    """{"comment":{"commentId":"x"}}"""
                }
            }
            respond(body, headers = headersOf(HttpHeaders.ContentType, "application/x-amz-json-1.1"))
        }
        val service = AwsCodeCommitPlatformService()
        service.client.rebuild("test") {
            AwsCodeSuiteClient(accessKeyId = "k", secretAccessKey = "s", httpClient = HttpClient(engine))
        }
        return service
    }

    private fun location(path: String) = """{"filePath":"$path","filePosition":3,"relativeFileVersion":"AFTER"}"""

    @Test
    fun `resolveMarkedInlineCommentsForFiles edits marked comments on re-reviewed files in place and deletes nothing`() =
        runTest {
            val count = service().resolveMarkedInlineCommentsForFiles("repo", "7", "<!-- kd -->", setOf("a.kt"))

            assertEquals(1, count)
            val write = writes.single()
            assertTrue(write.startsWith("UpdateComment "), write)
            assertTrue(write.contains("\"c1\"") && write.contains("Outdated"), write)
        }

    @Test
    fun `resolveMarkedInlineComments edits every pending marked inline comment`() =
        runTest {
            assertEquals(2, service().resolveMarkedInlineComments("repo", "7", "<!-- kd -->"))
            assertEquals(listOf("UpdateComment", "UpdateComment"), writes.map { it.substringBefore(' ') })
        }

    @Test
    fun `findAllMatchingComments returns every summary copy and deleteComment removes one`() =
        runTest {
            val service = service()

            assertEquals(listOf("s1", "s2"), service.findAllMatchingComments("repo", "7", "<!-- sum -->"))
            service.deleteComment("repo", "7", "s2")
            assertEquals(listOf("""DeleteCommentContent {"commentId":"s2"}"""), writes)
        }
}
