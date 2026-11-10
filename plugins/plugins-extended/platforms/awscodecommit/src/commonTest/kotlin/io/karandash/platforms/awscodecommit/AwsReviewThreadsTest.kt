@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.platforms.awscodecommit

import io.karandash.api.platform.UnsupportedPlatformOperationException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AwsReviewThreadsTest {
    private val writes = mutableListOf<String>()

    private suspend fun service(): AwsCodeCommitPlatformService {
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
                        """{"comments":[{"commentId":"g1","content":"general"}]},""" +
                        """{"location":{"filePath":"a.kt","filePosition":3,"relativeFileVersion":"AFTER"},"comments":[""" +
                        """{"commentId":"c1","content":"root","authorArn":"arn:aws:iam::1:user/dev"},""" +
                        """{"commentId":"c2","content":"reply","inReplyTo":"c1"},""" +
                        """{"commentId":"c3","content":"nested","inReplyTo":"c2"}]}]}"""
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

    @Test
    fun `listReviewThreads groups reply chains under the opening comment`() =
        runTest {
            val threads = service().listReviewThreads("repo", "7")

            assertEquals(listOf("g1", "c1"), threads.map { it.id })
            val inline = threads[1]
            assertEquals(listOf("c1", "c2", "c3"), inline.notes.map { it.id })
            assertEquals("dev", inline.notes.first().author)
            assertEquals("a.kt", inline.position?.newPath)
            assertEquals(3, inline.position?.newLine)
            assertEquals(null, threads[0].position)
        }

    @Test
    fun `replyToThread and updateNote send PostCommentReply and UpdateComment`() =
        runTest {
            val service = service()
            assertTrue(service.replyToThread("repo", "7", "c1", "thanks").isSuccess)
            assertTrue(service.updateNote("repo", "7", "c1", "c2", "edited").isSuccess)

            assertTrue(writes[0].startsWith("PostCommentReply ") && "\"inReplyTo\":\"c1\"" in writes[0], writes[0])
            assertTrue(writes[1].startsWith("UpdateComment ") && "\"commentId\":\"c2\"" in writes[1], writes[1])
        }

    @Test
    fun `resolveThread stays unsupported`() =
        runTest {
            assertIs<UnsupportedPlatformOperationException>(service().resolveThread("repo", "7", "c1").exceptionOrNull())
        }
}
