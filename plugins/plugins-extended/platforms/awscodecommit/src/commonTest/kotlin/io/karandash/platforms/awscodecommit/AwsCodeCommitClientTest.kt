package io.karandash.platforms.awscodecommit

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AwsCodeCommitClientTest {
    @Test
    fun testClientConstruction() =
        runTest {
            // Test default constructor
            val client1 = AwsCodeSuiteClient()
            assertNotNull(client1)
            client1.close()

            // Test with region
            val client2 = AwsCodeSuiteClient(region = "us-west-2")
            assertNotNull(client2)
            client2.close()

            // Test with credentials
            val client3 = AwsCodeSuiteClient(
                region = "eu-west-1",
                accessKeyId = "test-key",
                secretAccessKey = "test-secret"
            )
            assertNotNull(client3)
            client3.close()
        }

    @Test
    fun testBaseUrlConstruction() =
        runTest {
            val client1 = AwsCodeSuiteClient(region = "us-east-1")
            client1.close()

            val client2 = AwsCodeSuiteClient(region = "us-west-2")
            client2.close()

            val client3 = AwsCodeSuiteClient(region = "eu-central-1")
            client3.close()
        }

    @Test
    fun `explicit credentials sign requests with SigV4`() =
        runTest {
            val authorizations = mutableListOf<String?>()
            val amzDates = mutableListOf<String?>()
            val payloadHashes = mutableListOf<String?>()
            val securityTokens = mutableListOf<String?>()
            val engine = MockEngine { request ->
                authorizations += request.headers[HttpHeaders.Authorization]
                amzDates += request.headers["X-Amz-Date"]
                payloadHashes += request.headers["X-Amz-Content-Sha256"]
                securityTokens += request.headers["X-Amz-Security-Token"]
                respond(
                    content = """
                    {
                      "pullRequest": {
                        "pullRequestId": "1",
                        "title": "Test PR",
                        "pullRequestStatus": "OPEN",
                        "authorArn": "arn:aws:iam::123456789012:user/test",
                        "creationDate": "2024-01-01T00:00:00Z",
                        "pullRequestTargets": []
                      }
                    }
                    """.trimIndent(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/x-amz-json-1.1")
                )
            }

            val client = AwsCodeSuiteClient(
                region = "us-east-1",
                accessKeyId = "test-key",
                secretAccessKey = "test-secret",
                sessionToken = "test-session-token",
                httpClient = HttpClient(engine)
            )

            val pullRequest = client.fetchPullRequest("1")
            assertEquals("1", pullRequest.pullRequestId)
            assertTrue(authorizations.single().orEmpty().startsWith("AWS4-HMAC-SHA256 Credential=test-key/"))
            assertTrue(amzDates.single().orEmpty().endsWith("Z"))
            assertTrue(payloadHashes.single().orEmpty().isNotBlank())
            assertEquals("test-session-token", securityTokens.single())
            client.close()
        }

    @Test
    fun `explicit credentials require both key and secret`() {
        assertFailsWith<IllegalArgumentException> {
            AwsCodeSuiteClient(
                region = "us-east-1",
                accessKeyId = "test-key"
            )
        }
    }

    @Test
    fun `postCommentForPullRequest sends correct request and returns comment`() =
        runTest {
            val engine = MockEngine { request ->
                assertEquals("CodeCommit_20150413.PostCommentForPullRequest", request.headers["X-Amz-Target"])
                respond(
                    content = """
                    {
                      "comment": {
                        "commentId": "comment-123",
                        "content": "Test comment",
                        "authorArn": "arn:aws:iam::123456789012:user/test",
                        "creationDate": "2024-01-01T00:00:00Z"
                      }
                    }
                    """.trimIndent(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/x-amz-json-1.1")
                )
            }

            val client = AwsCodeSuiteClient(
                region = "us-east-1",
                accessKeyId = "test-key",
                secretAccessKey = "test-secret",
                httpClient = HttpClient(engine)
            )

            val comment = client.postCommentForPullRequest(
                repoName = "test-repo",
                prId = "1",
                content = "Test comment",
                beforeCommitId = "abc123",
                afterCommitId = "def456"
            )

            assertEquals("comment-123", comment.commentId)
            assertEquals("Test comment", comment.content)
            assertEquals("arn:aws:iam::123456789012:user/test", comment.authorArn)
            client.close()
        }

    @Test
    fun `getCommentsForPullRequest returns flattened comment list`() =
        runTest {
            val engine = MockEngine { request ->
                assertEquals("CodeCommit_20150413.GetCommentsForPullRequest", request.headers["X-Amz-Target"])
                respond(
                    content = """
                    {
                      "commentsForPullRequestData": [
                        {
                          "comments": [
                            {
                              "commentId": "comment-1",
                              "content": "First comment",
                              "authorArn": "arn:aws:iam::123456789012:user/user1"
                            },
                            {
                              "commentId": "comment-2",
                              "content": "Second comment",
                              "authorArn": "arn:aws:iam::123456789012:user/user2"
                            }
                          ]
                        },
                        {
                          "comments": [
                            {
                              "commentId": "comment-3",
                              "content": "Third comment",
                              "authorArn": "arn:aws:iam::123456789012:user/user3"
                            }
                          ]
                        }
                      ]
                    }
                    """.trimIndent(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/x-amz-json-1.1")
                )
            }

            val client = AwsCodeSuiteClient(
                region = "us-east-1",
                accessKeyId = "test-key",
                secretAccessKey = "test-secret",
                httpClient = HttpClient(engine)
            )

            val comments = client.getCommentsForPullRequest(
                repoName = "test-repo",
                prId = "1"
            )

            assertEquals(3, comments.size)
            assertEquals("comment-1", comments[0].commentId)
            assertEquals("comment-2", comments[1].commentId)
            assertEquals("comment-3", comments[2].commentId)
            client.close()
        }

    @Test
    fun `updateComment sends correct request`() =
        runTest {
            var targetHeader: String? = null
            val engine = MockEngine { request ->
                targetHeader = request.headers["X-Amz-Target"]
                respond(
                    content = """
                    {
                      "comment": {
                        "commentId": "comment-123",
                        "content": "Updated comment"
                      }
                    }
                    """.trimIndent(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/x-amz-json-1.1")
                )
            }

            val client = AwsCodeSuiteClient(
                region = "us-east-1",
                accessKeyId = "test-key",
                secretAccessKey = "test-secret",
                httpClient = HttpClient(engine)
            )

            client.updateComment(
                commentId = "comment-123",
                content = "Updated comment"
            )

            assertEquals("CodeCommit_20150413.UpdateComment", targetHeader)
            client.close()
        }

    @Test
    fun `updatePullRequestTitle sends correct request`() =
        runTest {
            var targetHeader: String? = null
            val engine = MockEngine { request ->
                targetHeader = request.headers["X-Amz-Target"]
                respond(
                    content = """
                    {
                      "pullRequest": {
                        "pullRequestId": "1",
                        "title": "Updated Title",
                        "pullRequestStatus": "OPEN",
                        "authorArn": "arn:aws:iam::123456789012:user/test",
                        "creationDate": "2024-01-01T00:00:00Z"
                      }
                    }
                    """.trimIndent(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/x-amz-json-1.1")
                )
            }

            val client = AwsCodeSuiteClient(
                region = "us-east-1",
                accessKeyId = "test-key",
                secretAccessKey = "test-secret",
                httpClient = HttpClient(engine)
            )

            client.updatePullRequestTitle(
                prId = "1",
                title = "Updated Title"
            )

            assertEquals("CodeCommit_20150413.UpdatePullRequestTitle", targetHeader)
            client.close()
        }

    @Test
    fun `updatePullRequestDescription sends correct request`() =
        runTest {
            var targetHeader: String? = null
            val engine = MockEngine { request ->
                targetHeader = request.headers["X-Amz-Target"]
                respond(
                    content = """
                    {
                      "pullRequest": {
                        "pullRequestId": "1",
                        "title": "Test PR",
                        "description": "Updated description",
                        "pullRequestStatus": "OPEN",
                        "authorArn": "arn:aws:iam::123456789012:user/test",
                        "creationDate": "2024-01-01T00:00:00Z"
                      }
                    }
                    """.trimIndent(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/x-amz-json-1.1")
                )
            }

            val client = AwsCodeSuiteClient(
                region = "us-east-1",
                accessKeyId = "test-key",
                secretAccessKey = "test-secret",
                httpClient = HttpClient(engine)
            )

            client.updatePullRequestDescription(
                prId = "1",
                description = "Updated description"
            )

            assertEquals("CodeCommit_20150413.UpdatePullRequestDescription", targetHeader)
            client.close()
        }

    @Test
    fun `getCommentsForPullRequest follows nextToken pagination`() =
        runTest {
            var requestCount = 0
            val engine = MockEngine { request ->
                assertEquals("CodeCommit_20150413.GetCommentsForPullRequest", request.headers["X-Amz-Target"])
                requestCount++
                when (requestCount) {
                    1 -> respond(
                        content = """
                        {
                          "commentsForPullRequestData": [
                            {
                              "comments": [
                                {
                                  "commentId": "comment-1",
                                  "content": "Page 1 comment",
                                  "authorArn": "arn:aws:iam::123456789012:user/user1"
                                }
                              ]
                            }
                          ],
                          "nextToken": "token-page-2"
                        }
                        """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/x-amz-json-1.1")
                    )

                    else -> respond(
                        content = """
                        {
                          "commentsForPullRequestData": [
                            {
                              "comments": [
                                {
                                  "commentId": "comment-2",
                                  "content": "Page 2 comment",
                                  "authorArn": "arn:aws:iam::123456789012:user/user2"
                                }
                              ]
                            }
                          ]
                        }
                        """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/x-amz-json-1.1")
                    )
                }
            }

            val client = AwsCodeSuiteClient(
                region = "us-east-1",
                accessKeyId = "test-key",
                secretAccessKey = "test-secret",
                httpClient = HttpClient(engine)
            )

            val comments = client.getCommentsForPullRequest(
                repoName = "test-repo",
                prId = "1"
            )

            assertEquals(2, requestCount)
            assertEquals(2, comments.size)
            assertEquals("comment-1", comments[0].commentId)
            assertEquals("comment-2", comments[1].commentId)
            client.close()
        }

    // Note: Actual HTTP tests would require mocking the HTTP client
    // These tests verify the client can be constructed and closed properly
    // Integration tests with real AWS API would be in a separate test suite
}
