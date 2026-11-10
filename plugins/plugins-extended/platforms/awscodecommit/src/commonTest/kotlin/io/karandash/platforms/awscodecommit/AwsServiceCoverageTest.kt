@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.platforms.awscodecommit

import io.karandash.api.ModuleRegistryImpl
import io.karandash.api.PluginContext
import io.karandash.api.platform.PlatformOperation
import io.karandash.api.support.mapToJsonElement
import io.karandash.crypto.AwsCredentialChain
import io.karandash.http.HttpClientSettings
import io.karandash.http.ProviderHttpClientFactory
import io.karandash.models.support.KarandashJson
import io.karandash.sdk.Logger
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AwsServiceCoverageTest {
    private class CapturingLogger : Logger {
        val warnings = mutableListOf<String>()

        override fun debug(message: String) = Unit

        override fun info(message: String) = Unit

        override fun warn(message: String) {
            warnings += message
        }

        override fun error(message: String) = Unit
    }

    private val json = headersOf(HttpHeaders.ContentType, "application/x-amz-json-1.1")

    private fun prJson(
        revision: String? = "rev-1",
        targets: String =
            """[{"repositoryName":"repo","sourceReference":"refs/heads/f","destinationReference":"refs/heads/main",
            "sourceCommit":"src1","destinationCommit":"dst1","mergeBase":"dst1",
            "mergeMetadata":{"isMerged":true,"mergedBy":"arn:u","mergeCommitId":"m1","mergeOption":"SQUASH_MERGE"}}]""",
    ): String {
        val rev = revision?.let { ""","revisionId":"$it"""" }.orEmpty()
        return """{"pullRequest":{"pullRequestId":"7","title":"t","description":"d","pullRequestStatus":"OPEN",
            "authorArn":"arn:aws:iam::1:user/dev","creationDate":"1.7E9","lastActivityDate":"1.7E9",
            "approvalRules":[{"approvalRuleId":"r1","approvalRuleName":"n","approvalRuleContent":"c","ruleContentSha256":"s",
            "lastModifiedDate":"1","creationDate":"1","lastModifiedUser":"u",
            "originApprovalRuleTemplate":{"approvalRuleTemplateId":"t1","approvalRuleTemplateName":"tn"}}],
            "pullRequestTargets":$targets$rev}}"""
    }

    private suspend fun service(
        pr: String = prJson(),
        blob: (String) -> Pair<HttpStatusCode, String> = { HttpStatusCode.OK to """{"content":"aGVsbG8="}""" },
        logger: Logger? = null,
    ): AwsCodeCommitPlatformService {
        val engine = MockEngine { request ->
            val body = (request.body as TextContent).text
            when (request.headers["X-Amz-Target"]) {
                "CodeCommit_20150413.GetPullRequest" -> {
                    respond(pr, headers = json)
                }

                "CodeCommit_20150413.GetCommit" -> {
                    respond(
                        """{"commit":{"commitId":"src1","parents":["dst1"],"message":"m","author":{"name":"a","email":"e","date":"1"}}}""",
                        headers = json,
                    )
                }

                "CodeCommit_20150413.GetDifferences" -> {
                    respond(
                        """{"differences":[{"changeType":"M","beforeBlob":{"blobId":"b1","path":"a.kt"},"afterBlob":{"blobId":"b2","path":"a.kt"}},
                    {"changeType":"A","afterBlob":{"blobId":"b3"}}]}""",
                        headers = json,
                    )
                }

                "CodeCommit_20150413.GetBlob" -> {
                    val (status, text) = blob(body)
                    respond(text, status, json)
                }

                "CodeCommit_20150413.GetPullRequestApprovalStates" -> {
                    respond(
                        """{"approvals":[{"userArn":"arn:u","approvalState":"APPROVE"}]}""",
                        headers = json,
                    )
                }

                else -> {
                    respond(pr, headers = json)
                }
            }
        }
        val svc = AwsCodeCommitPlatformService(credentialChain = { AwsCredentialChain(env = { null }) })
        assertTrue(svc.initialize(context(mapOf("accessKeyId" to "AKID", "secretAccessKey" to "secret"))))
        svc.client.rebuild("test") {
            AwsCodeSuiteClient(
                region = "us-east-1",
                accessKeyId = "AKID",
                secretAccessKey = "secret",
                engine = engine,
                logger = logger ?: io.karandash.sdk.NoOpLogger,
            )
        }
        return svc
    }

    @Test
    fun `fetchMergeRequest assembles commits changes and approvals`() =
        runTest {
            val mr = service().fetchMergeRequest("repo", "7")

            assertEquals("t", mr.title)
            assertEquals(1, mr.commits.size)
            assertEquals(2, mr.changes.size)
            assertEquals(1, mr.approvals.size)
            assertNotNull(mr.diffRefs)
        }

    @Test
    fun `fetchMergeRequest without a revision id has no approvals`() =
        runTest {
            val mr = service(pr = prJson(revision = null)).fetchMergeRequest("repo", "7")
            assertTrue(mr.approvals.isEmpty())
        }

    @Test
    fun `fetchMergeRequest skips files whose blob cannot be read`() =
        runTest {
            val logger = CapturingLogger()
            val mr = service(blob = { HttpStatusCode.NotFound to """{"message":"gone"}""" }, logger = logger)
                .fetchMergeRequest("repo", "7")

            assertEquals(2, mr.changes.size)
            assertTrue(logger.warnings.any { it.contains("failed (HTTP 404)") }, "${logger.warnings}")
        }

    @Test
    fun `fetchMergeRequest rejects pull requests without targets or commits`() =
        runTest {
            assertFailsWith<IllegalArgumentException> { service(pr = prJson(targets = "[]")).fetchMergeRequest("repo", "7") }
            val noSource = """[{"repositoryName":"repo","sourceReference":"s","destinationReference":"d","destinationCommit":"d1"}]"""
            assertFailsWith<IllegalArgumentException> { service(pr = prJson(targets = noSource)).fetchMergeRequest("repo", "7") }
            val noDest = """[{"repositoryName":"repo","sourceReference":"s","destinationReference":"d","sourceCommit":"s1"}]"""
            assertFailsWith<IllegalArgumentException> { service(pr = prJson(targets = noDest)).fetchMergeRequest("repo", "7") }
        }

    @Test
    fun `fetchMergeRequestByUrl parses the console url`() =
        runTest {
            val svc = service()
            val url = "https://console.aws.amazon.com/codesuite/codecommit/repositories/repo/pull-requests/7?region=us-east-1"
            assertEquals("t", svc.fetchMergeRequestByUrl(url).title)
            assertFailsWith<IllegalArgumentException> { svc.fetchMergeRequestByUrl("https://example.com/nope") }
        }

    @Test
    fun `title description and merge reject pull requests without usable targets`() =
        runTest {
            service().updateTitle("repo", "7", "new")
            service().updateDescription("repo", "7", "new")
            assertTrue(service(pr = prJson(targets = "[]")).mergeMr("repo", "7").isFailure)
            val noSource = """[{"repositoryName":"repo","sourceReference":"s","destinationReference":"d","destinationCommit":"d1"}]"""
            assertTrue(service(pr = prJson(targets = noSource)).mergeMr("repo", "7").isFailure)
        }

    @Test
    fun `unapprove and approve resolve the revision`() =
        runTest {
            service().unapprove("repo", "7")
            service().approve("repo", "7")
            assertFailsWith<IllegalArgumentException> { service(pr = prJson(revision = null)).approve("repo", "7") }
        }

    @Test
    fun `checkAuth is null without explicit credentials`() =
        runTest {
            val svc = AwsCodeCommitPlatformService(credentialChain = { AwsCredentialChain(env = { null }) })
            assertTrue(svc.initialize(context(emptyMap())))
            assertNull(svc.checkAuth())
        }

    @Test
    fun `metadata helpers describe the platform`() =
        runTest {
            val svc = AwsCodeCommitPlatformService()
            assertEquals("awsCodeCommit", svc.dslName)
            assertEquals(60, svc.defaultPriority)
            assertTrue(svc.description.contains("CodeCommit"))
            assertEquals(setOf("codecommit"), svc.aliases())
            assertTrue(svc.authTokenHint().contains("AWS_ACCESS_KEY_ID"))
            assertEquals(5, svc.unavailableMergeRequestFields().size)
            assertTrue(svc.supports(PlatformOperation.MERGE))
            assertNull(svc.rateLimitStatus())
            assertTrue(svc.fetchFileBlame("repo", "a.kt", "main").isEmpty())
            svc.shutdown()
        }

    @Test
    fun `ci environment and url parsing`() =
        runTest {
            val svc = AwsCodeCommitPlatformService()
            val env = mapOf(
                "AWS_REGION" to "eu-west-1",
                "CODEBUILD_BUILD_ID" to "b",
                "CODEBUILD_SOURCE_REPO_URL" to "https://git-codecommit.eu-west-1.amazonaws.com/v1/repos/my-repo",
                "CODEBUILD_SOURCE_VERSION" to "pr/12",
            )
            val ci = assertNotNull(svc.detectCiEnvironment { env[it] })
            assertEquals("my-repo", ci.projectId)
            assertEquals("12", ci.mrId)
            assertEquals("https://codecommit.eu-west-1.amazonaws.com", ci.baseUrl)
            assertNull(svc.detectCiEnvironment { null })
            assertNull(svc.detectCiEnvironment { if (it == "AWS_REGION") "us-east-1" else null })
            assertNull(svc.detectCiEnvironment { if (it == "CODEBUILD_BUILD_ID") null else env[it] })
            assertNull(svc.detectCiEnvironment { if (it == "CODEBUILD_SOURCE_REPO_URL") null else env[it] })
            assertNull(svc.detectCiEnvironment { if (it == "CODEBUILD_SOURCE_REPO_URL") "https://github.com/o/r" else env[it] })

            val parsed = assertNotNull(
                svc.parseMergeRequestUrl("https://eu-west-1.console.aws.amazon.com/codesuite/codecommit/repositories/r/pull-requests/3"),
            )
            assertEquals("r", parsed.projectId)
            assertEquals("3", parsed.mrId)
            assertNull(svc.parseMergeRequestUrl("https://example.com/x"))
            assertNull(svc.parseMergeRequestUrl("https://example.com/repositories/r/pull-requests/3"))

            assertEquals("my-repo", svc.parseGitRemoteUrl("https://git-codecommit.us-east-1.amazonaws.com/v1/repos/my-repo"))
            assertEquals("my-repo", svc.parseGitRemoteUrl("ssh://git-codecommit.us-east-1.amazonaws.com/v1/repos/my-repo"))
            assertNull(svc.parseGitRemoteUrl("https://github.com/o/r"))
        }

    @Test
    fun `client rejects a bad region and a pull request without revision`() =
        runTest {
            assertFailsWith<IllegalArgumentException> { AwsCodeSuiteClient(region = "nowhere") }
            val engine = MockEngine { respond(prJson(revision = null), headers = json) }
            val client = AwsCodeSuiteClient(accessKeyId = "k", secretAccessKey = "s", engine = engine)
            assertFailsWith<IllegalArgumentException> { client.currentRevisionId("7") }
        }

    @Test
    fun `fetchCommitsBetween warns when the walk hits the cap`() =
        runTest {
            val logger = CapturingLogger()
            var n = 0
            val engine = MockEngine {
                n++
                respond("""{"commit":{"commitId":"c$n","parents":["c${n + 1}"]}}""", headers = json)
            }
            val client = AwsCodeSuiteClient(accessKeyId = "k", secretAccessKey = "s", engine = engine, logger = logger)

            assertEquals(250, client.fetchCommitsBetween("repo", "base", "c0").size)
            assertTrue(logger.warnings.single().contains("older ones are missing"))
        }

    @Test
    fun `fetchCommitsBetween walks every commit when the page cap is off`() =
        runTest {
            var n = 0
            val engine = MockEngine {
                n++
                val parents = if (n < 300) """["c${n + 1}"]""" else "[]"
                respond("""{"commit":{"commitId":"c$n","parents":$parents}}""", headers = json)
            }
            val client = AwsCodeSuiteClient(accessKeyId = "k", secretAccessKey = "s", engine = engine)
            val session = ModuleRegistryImpl().forRun()
            // http.pagination.maxPages: 0
            session.provide(ProviderHttpClientFactory.HTTP_SETTINGS, HttpClientSettings(maxPages = Int.MAX_VALUE))

            assertEquals(300, withContext(session.services) { client.fetchCommitsBetween("repo", "base", "c0") }.size)
        }

    @Test
    fun `fetchBlob is null for a body without content`() =
        runTest {
            val logger = CapturingLogger()
            val engine = MockEngine { respond("{}", headers = json) }
            val client = AwsCodeSuiteClient(accessKeyId = "k", secretAccessKey = "s", engine = engine, logger = logger)

            assertNull(client.fetchBlob("repo", "b1"))
            assertTrue(logger.warnings.single().contains("no content"))
        }

    @Test
    fun `isApprovedByCurrentCaller is null when the approval lookup fails`() =
        runTest {
            val logger = CapturingLogger()
            val engine = MockEngine { request ->
                if (request.url.host.startsWith("sts.")) {
                    respond(STS_RESPONSE)
                } else {
                    respond("""{"message":"x"}""", HttpStatusCode.InternalServerError, json)
                }
            }
            val client = AwsCodeSuiteClient(accessKeyId = "k", secretAccessKey = "s", engine = engine, logger = logger)

            assertNull(client.isApprovedByCurrentCaller("7", "rev"))
            assertTrue(logger.warnings.any { it.contains("isApprovedByCurrentCaller failed") }, "${logger.warnings}")
        }

    @Test
    fun `adapter handles a target without commits and a difference without paths`() {
        val pr = KarandashJson.Default.decodeFromString<AwsPullRequest>(
            """{"pullRequestId":"1","title":"t","pullRequestStatus":"OPEN","authorArn":"a","creationDate":"1",
            "pullRequestTargets":[{"repositoryName":"r","sourceReference":"s","destinationReference":"d"}]}""",
        )
        val diff = AwsDifference(changeType = "M")
        val mr = AwsCodeSuiteAdapter.toMergeRequest(pr, emptyList(), listOf(diff), emptyList())

        assertNull(mr.diffRefs)
        assertEquals("unknown", mr.changes.single().path)
        assertFailsWith<IllegalArgumentException> {
            AwsCodeSuiteAdapter.toMergeRequest(pr.copy(pullRequestTargets = emptyList()), emptyList(), emptyList(), emptyList())
        }
    }

    @Test
    fun `models decode a full realistic payload`() {
        val pr = KarandashJson.Default.decodeFromString<AwsPullRequest>(prJson().substringAfter("\"pullRequest\":").dropLast(1))
        val rule = pr.approvalRules.single()
        assertEquals("tn", rule.originApprovalRuleTemplate?.approvalRuleTemplateName)
        assertEquals(
            true,
            pr.pullRequestTargets
                .single()
                .mergeMetadata
                ?.isMerged
        )
        assertEquals("dst1", pr.pullRequestTargets.single().mergeBase)

        val comment = KarandashJson.Default.decodeFromString<AwsComment>(
            """{"commentId":"c","content":"x","authorArn":"a","creationDate":"1","lastModifiedDate":"2","inReplyTo":"p",
            "clientRequestToken":"t","filePath":"a.kt"}""",
        )
        assertEquals("a.kt", comment.filePath)
        val location = KarandashJson.Default.decodeFromString<AwsLocation>(
            """{"filePath":"a.kt","filePosition":3,"relativeFileVersion":"AFTER"}""",
        )
        assertEquals(3L, location.filePosition)
        val commit = KarandashJson.Default.decodeFromString<AwsCommit>(
            """{"commitId":"c","treeId":"t","parents":["p"],"message":"m","additionalData":"x",
            "author":{"name":"n"},"committer":{"email":"e","date":"d"}}""",
        )
        assertEquals("p", commit.parents.single())
        val differences = KarandashJson.Default.decodeFromString<AwsDifferencesResponse>(
            """{"differences":[{"changeType":"D","beforeBlob":{"blobId":"b","path":"p","mode":"100644"}}],"NextToken":"n"}""",
        )
        assertEquals("n", differences.nextToken)
    }

    private fun context(config: Map<String, String>): PluginContext =
        object : PluginContext {
            override val pluginConfig = mapToJsonElement(config)
            override val hostVersion = "1.0.0-test"
            override val workingDirectory = "/tmp/test"

            override fun env(name: String): String? = null

            override fun log(message: String) {}

            override fun warn(message: String) {}
        }

    private companion object {
        const val STS_RESPONSE =
            "<GetCallerIdentityResponse><GetCallerIdentityResult><Arn>arn:u</Arn></GetCallerIdentityResult></GetCallerIdentityResponse>"
    }
}
