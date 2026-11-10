package io.karandash.platforms.azuredevops

import io.karandash.api.PluginContext
import io.karandash.api.support.mapToJsonElement
import io.karandash.models.ChangeType
import io.karandash.models.support.KarandashJson
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AzureDevOpsServiceCoverageTest {
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    private val prJson =
        """{"pullRequestId":7,"title":"t","status":"active","createdBy":{"id":"u1","displayName":"U","uniqueName":"u@x"},
        "creationDate":"2024-01-01T00:00:00Z","sourceRefName":"refs/heads/f","targetRefName":"refs/heads/main",
        "reviewers":[{"id":"r1","displayName":"R","uniqueName":"r@x","vote":10}],
        "lastMergeSourceCommit":{"commitId":"s1"},"lastMergeTargetCommit":{"commitId":"t1"},"mergeStatus":"rejectedByPolicy"}"""

    private val changesJson =
        """{"changeEntries":[{"item":{"path":"/a.kt","objectId":"o1","originalObjectId":"o0"},"changeType":"edit"},
        {"item":{"path":"/b.kt","originalObjectId":"o2"},"changeType":"delete"},
        {"item":{"path":"/c.kt","objectId":"o3"},"changeType":"rename"},{"changeType":"add"}]}"""

    private suspend fun service(
        vote: Int? = 10,
        statusesOk: Boolean = false,
    ): AzureDevOpsPlatformService {
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            when {
                request.method != HttpMethod.Get -> {
                    respond("{}", HttpStatusCode.OK, jsonHeaders)
                }

                path.endsWith("/reviewers/me") -> {
                    if (vote == null) {
                        respond("boom", HttpStatusCode.InternalServerError)
                    } else {
                        respond("""{"vote":$vote}""", HttpStatusCode.OK, jsonHeaders)
                    }
                }

                path.endsWith("/statuses") -> {
                    if (statusesOk) {
                        respond(
                            """{"value":[{"state":"succeeded","context":{"name":"build","genre":"ci"}},
                        {"state":"notApplicable","context":{"name":"x"}}]}""",
                            HttpStatusCode.OK,
                            jsonHeaders,
                        )
                    } else {
                        respond("down", HttpStatusCode.InternalServerError)
                    }
                }

                path.endsWith("/commits") && !path.contains("pullrequests", ignoreCase = true) -> {
                    respond(
                        """{"value":[{"commitId":"c1","comment":"m","author":{"name":"a","email":"e","date":"d"},
                        "committer":{"name":"a","email":"e","date":"d"}}],"count":1}""",
                        HttpStatusCode.OK,
                        jsonHeaders,
                    )
                }

                path.endsWith("/commits") -> {
                    respond("""{"value":[],"count":0}""", HttpStatusCode.OK, jsonHeaders)
                }

                path.endsWith("/iterations") -> {
                    respond("""{"value":[{"id":1}]}""", HttpStatusCode.OK, jsonHeaders)
                }

                path.endsWith("/changes") -> {
                    respond(changesJson, HttpStatusCode.OK, jsonHeaders)
                }

                path.contains("/blobs/") -> {
                    respond("text", HttpStatusCode.OK)
                }

                path.endsWith("/pullrequests/7") -> {
                    respond(prJson, HttpStatusCode.OK, jsonHeaders)
                }

                else -> {
                    respond("nope", HttpStatusCode.NotFound)
                }
            }
        }
        val svc = AzureDevOpsPlatformService(engine)
        assertTrue(svc.initialize(context(mapOf("token" to "t"))))
        return svc
    }

    @Test
    fun `fetchMergeRequest maps changes and degrades CI status when statuses fail`() =
        runTest {
            val mr = service().fetchMergeRequest("org/proj/repo", "7")

            assertEquals("t", mr.title)
            assertEquals("cannot_be_merged", mr.mergeStatus)
            assertEquals(listOf(ChangeType.MODIFIED, ChangeType.DELETED, ChangeType.RENAMED), mr.changes.map { it.changeType })
            assertTrue(mr.ciJobs.isEmpty())
            assertNotNull(mr.diffRefs)
        }

    @Test
    fun `fetchMergeRequest reports CI jobs from statuses`() =
        runTest {
            val mr = service(statusesOk = true).fetchMergeRequest("org/proj/repo", "7")
            assertEquals(listOf("ci/build"), mr.ciJobs.map { it.name })
        }

    @Test
    fun `fetchMergeRequestByUrl needs a pull request url`() =
        runTest {
            val svc = service()
            assertEquals("t", svc.fetchMergeRequestByUrl("https://dev.azure.com/org/proj/_git/repo/pullrequest/7").title)
            assertFailsWith<IllegalArgumentException> { svc.fetchMergeRequestByUrl("https://example.com/x") }
        }

    @Test
    fun `listing open ids fails before initialization`() =
        runTest {
            assertFailsWith<IllegalStateException> { AzureDevOpsPlatformService().listOpenMergeRequestIds("org/proj/repo", "main", 5) }
        }

    @Test
    fun `blame comes from file commits and is empty on failure or before initialization`() =
        runTest {
            assertTrue(AzureDevOpsPlatformService().fetchFileBlame("org/proj/repo", "a.kt", "main").isEmpty())
            val svc = service()
            assertEquals(1, svc.fetchFileBlame("org/proj/repo", "a.kt", "main").size)
            assertTrue(svc.fetchFileBlame("bad-project-id", "a.kt", "main").isEmpty())
        }

    @Test
    fun `title description and votes go through the comment client`() =
        runTest {
            val svc = service(vote = 0)
            svc.updateTitle("org/proj/repo", "7", "n")
            svc.updateDescription("org/proj/repo", "7", "d")
            svc.approve("org/proj/repo", "7")
            svc.unapprove("org/proj/repo", "7")
            service(vote = 10).approve("org/proj/repo", "7")
            service(vote = 10).unapprove("org/proj/repo", "7")
            service(vote = null).unapprove("org/proj/repo", "7")
        }

    @Test
    fun `metadata and rate limit helpers`() =
        runTest {
            val svc = AzureDevOpsPlatformService()
            assertEquals("azureDevOps", svc.dslName)
            assertEquals(50, svc.defaultPriority)
            assertEquals(setOf("azure", "ado"), svc.aliases())
            assertEquals("https://dev.azure.com", svc.defaultApiUrl())
            assertEquals("AZURE_DEVOPS_TOKEN", svc.authTokenHint())
            assertTrue(svc.permissionScope().contains("Code (Read)"))
            assertTrue(svc.tokenLocationHint().contains("Personal access tokens"))
            assertTrue(svc.description.contains("Azure DevOps"))
            assertNull(svc.rateLimitStatus())
        }

    @Test
    fun `ci environment is built from pipeline variables`() =
        runTest {
            val svc = AzureDevOpsPlatformService()
            val env = mapOf(
                "TF_BUILD" to "True",
                "SYSTEM_TEAMFOUNDATIONCOLLECTIONURI" to "https://dev.azure.com/acme/",
                "SYSTEM_TEAMPROJECT" to "proj",
                "BUILD_REPOSITORY_NAME" to "repo",
                "SYSTEM_PULLREQUEST_PULLREQUESTID" to "9",
                "SYSTEM_ACCESSTOKEN" to "tok",
            )
            val ci = assertNotNull(svc.detectCiEnvironment { env[it] })
            assertEquals("acme/proj/repo", ci.projectId)
            assertEquals("9", ci.mrId)
            assertEquals("tok", ci.token)
            assertEquals("https://dev.azure.com/acme/proj/_apis", ci.baseUrl)

            assertNull(svc.detectCiEnvironment { null })

            val noProject = assertNotNull(
                svc.detectCiEnvironment {
                    mapOf(
                        "TF_BUILD" to "1",
                        "SYSTEM_TEAMFOUNDATIONCOLLECTIONURI" to "https://acme.visualstudio.com",
                        "BUILD_REPOSITORY_NAME" to "r"
                    )[it]
                },
            )
            assertEquals("https://acme.visualstudio.com", noProject.baseUrl)
            assertEquals("r", noProject.projectId)

            val server = assertNotNull(
                svc.detectCiEnvironment {
                    mapOf(
                        "SYSTEM_TEAMFOUNDATIONSERVERURI" to "https://tfs.example/",
                        "AZURE_HTTP_USER_AGENT" to "x"
                    )[it]
                },
            )
            assertEquals("https://tfs.example", server.baseUrl)

            val bareHost = assertNotNull(
                svc.detectCiEnvironment {
                    mapOf(
                        "TF_BUILD" to "1",
                        "SYSTEM_TEAMFOUNDATIONCOLLECTIONURI" to "https://tfs.example",
                        "SYSTEM_TEAMPROJECT" to "p",
                        "BUILD_REPOSITORY_NAME" to "r",
                    )[it]
                },
            )
            assertEquals("r", bareHost.projectId)
        }

    @Test
    fun `url and remote parsing`() =
        runTest {
            val svc = AzureDevOpsPlatformService()
            val parsed = assertNotNull(svc.parseMergeRequestUrl("https://dev.azure.com/org/proj/_git/repo/pullrequest/3"))
            assertEquals("org/proj/repo", parsed.projectId)
            assertNull(svc.parseMergeRequestUrl("https://example.com/x"))
            assertEquals("org/proj/repo", svc.parseGitRemoteUrl("git@ssh.dev.azure.com:v3/org/proj/repo"))
            assertEquals("org/proj/repo", svc.parseGitRemoteUrl("https://user@dev.azure.com/org/proj/_git/repo"))
            assertNull(svc.parseGitRemoteUrl("https://github.com/o/r"))
        }

    @Test
    fun `provider client tolerates unreadable optional endpoints`() =
        runTest {
            val engine = MockEngine { request ->
                if (request.url.encodedPath.endsWith("/iterations")) {
                    respond("<html>sign in</html>", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/html"))
                } else {
                    error("connection reset")
                }
            }
            val http = io.ktor.client.HttpClient(engine)
            val client = AzureDevOpsProviderClient(httpClient = http, token = "t")
            assertTrue(client.fetchChanges("o", "p", "r", "1").isEmpty())
            assertTrue(client.fetchFileCommits("o", "p", "r", "a.kt", "main").isEmpty())
        }

    @Test
    fun `adapter maps blame and statuses`() {
        val commit = AzureDevOpsCommit(
            commitId = "c1",
            comment = "m",
            author = AzureDevOpsCommitAuthor("a", "e", "d"),
            committer = AzureDevOpsCommitAuthor("a", "e", "d"),
        )
        assertEquals(
            "c1",
            AzureDevOpsAdapter
                .toBlameEntries(listOf(commit))
                .single()
                .commit.sha
        )
        val jobs = AzureDevOpsAdapter.toCiJobs(
            listOf(
                AzureDevOpsPullRequestStatus("failed", AzureDevOpsStatusContext("b", "ci")),
                AzureDevOpsPullRequestStatus("succeeded", AzureDevOpsStatusContext("b", "ci")),
                AzureDevOpsPullRequestStatus("notApplicable", AzureDevOpsStatusContext("z")),
            ),
        )
        assertEquals(listOf("succeeded"), jobs.map { it.status })
    }

    @Test
    fun `credentials fall back through the environment`() {
        assertEquals("a", AzureDevOpsCredentials.resolve(mapToJsonElement(mapOf("token" to "a")), { "env" }).token)
        assertEquals("env", AzureDevOpsCredentials.resolve(mapToJsonElement(emptyMap<String, Any?>()), { "env" }).token)
        assertEquals(
            "sys",
            AzureDevOpsCredentials
                .resolve(kotlinx.serialization.json.JsonNull) {
                    if (it ==
                        "SYSTEM_ACCESSTOKEN"
                    ) {
                        "sys"
                    } else {
                        null
                    }
                }.token
        )
        assertEquals(setOf("AZURE_DEVOPS_TOKEN", "SYSTEM_ACCESSTOKEN"), AzureDevOpsCredentials.supportedEnvVars())
    }

    @Test
    fun `models decode realistic payloads`() {
        val json = KarandashJson.Default
        val pr = json.decodeFromString<AzurePullRequest>(
            """{"pullRequestId":1,"title":"t","description":"d","status":"active",
            "createdBy":{"id":"i","displayName":"n","uniqueName":"u","imageUrl":"x"},
            "sourceRefName":"s","targetRefName":"t","creationDate":"c","closedDate":"d",
            "labels":[{"id":"l","name":"bug","active":false}],
            "reviewers":[{"reviewerId":"r","displayName":"n","uniqueName":"u","vote":5,"isContainer":true,"isRequired":true}],
            "url":"u","lastMergeSourceCommit":{"commitId":"c"}}""",
        )
        assertEquals("bug", pr.labels.single().name)
        assertEquals(5, pr.reviewers.single().vote)
        val threads = json.decodeFromString<AzureThreadsResponse>(
            """{"count":1,"value":[{"id":1,"status":"active","publishedDate":"p","url":"u",
            "comments":[{"id":1,"content":"c","publishedDate":"p","url":"u","author":{"id":"i"}}],
            "threadContext":{"filePath":"/a","rightFileStart":{"line":1,"offset":2},"rightFileEnd":{"line":3,"offset":4}}}]}""",
        )
        assertEquals(
            3,
            threads.value
                .single()
                .threadContext
                ?.rightFileEnd
                ?.line
        )
        val comment = json.decodeFromString<AzureComment>(
            """{"id":1,"threadId":2,"status":"s","createdDate":"d","url":"u","comments":[{"id":1}]}""",
        )
        assertEquals(2, comment.threadId)
        val requests = listOf(
            json.encodeToString(PostCommentRequest.serializer(), PostCommentRequest(listOf(CommentContentRequest("c")), "active")),
            json.encodeToString(UpdateThreadRequest.serializer(), UpdateThreadRequest("c")),
            json.encodeToString(AddLabelRequest.serializer(), AddLabelRequest("l")),
            json.encodeToString(
                PostInlineCommentRequest.serializer(),
                PostInlineCommentRequest(listOf(InlineCommentContentRequest("c", 1)), "active", AzureThreadContext("/a")),
            ),
        )
        assertTrue(requests.all { it.isNotEmpty() })
        val commits = json.decodeFromString<AzureDevOpsCommitsResponse>(
            """{"count":1,"value":[{"commitId":"c","comment":"m","author":{"name":"a","email":"e","date":"d"},
            "committer":{"name":"a","email":"e","date":"d"},"changeCounts":{"Add":1,"Edit":2,"Delete":3},"url":"u"}]}""",
        )
        assertEquals(
            2,
            commits.value
                .single()
                .changeCounts
                ?.edit
        )
    }

    private fun context(config: Map<String, Any?>): PluginContext =
        object : PluginContext {
            override val pluginConfig = mapToJsonElement(config)
            override val hostVersion = "1.0.0-test"
            override val workingDirectory = "/tmp/test"

            override fun env(name: String): String? = null

            override fun log(message: String) {}

            override fun warn(message: String) {}
        }
}
