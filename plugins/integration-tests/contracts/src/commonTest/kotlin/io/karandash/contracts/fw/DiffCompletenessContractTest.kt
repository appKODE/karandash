package io.karandash.contracts.fw

import io.karandash.contracts.support.DIFF_SECRET_LINE
import io.karandash.contracts.support.UNIFIED_DIFF_HUNK
import io.karandash.contracts.support.initialized
import io.karandash.models.MergeRequest
import io.karandash.platforms.awscodecommit.AwsCodeCommitPlatformService
import io.karandash.platforms.awscodecommit.AwsCodeSuiteClient
import io.karandash.platforms.azuredevops.AzureDevOpsPlatformService
import io.karandash.platforms.azuredevops.AzureDevOpsProviderClient
import io.karandash.platforms.bitbucket.BitbucketClient
import io.karandash.platforms.bitbucket.BitbucketPlatformService
import io.karandash.platforms.gerrit.GerritPlatformService
import io.karandash.platforms.gerrit.GerritProviderClient
import io.karandash.platforms.gitea.GiteaPlatformService
import io.karandash.platforms.gitea.GiteaProviderClient
import io.karandash.testfixtures.AMZ_TARGET
import io.karandash.testfixtures.MockRoute
import io.karandash.testfixtures.RecordingMockServer
import io.karandash.testfixtures.amzTarget
import io.karandash.testfixtures.pathEndsWith
import io.ktor.client.request.HttpRequestData
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Bitbucket, Azure DevOps, Gitea, Gerrit and AWS CodeCommit build
 * `FileChange` without `diffContent`, so every diff-based rule (secrets, AI review, size-by-hunk) sees nothing.
 * Each platform's mock serves its real diff-bearing endpoint, and the test asserts the diff reaches
 * `MergeRequest.changes`. Composition sites at HEAD:
 * - Bitbucket: `BitbucketPlatformService.fetchPullRequest` (lines 300-312) fetches only `/diffstat`.
 * - Azure DevOps: `AzureDevOpsPlatformService.fetchPullRequest` (lines 345-358) fetches `/pullrequests/{id}/changes`,
 *   which is not a real endpoint; `AzureDevOpsProviderClient.fetchChanges` swallows the 404.
 * - Gitea: `GiteaPlatformService.fetchMergeRequest` (lines 100-118); `GiteaFile` has no patch field.
 * - Gerrit: `GerritPlatformService.fetchMergeRequest` (lines 96-106) maps `GerritFileInfo` line counts only.
 * - AWS: `AwsCodeCommitPlatformService.fetchMergeRequest` (lines 95-130) never calls `GetBlob`.
 */
class DiffCompletenessContractTest {
    private class PlatformCase(
        val name: String,
        val routes: List<MockRoute>,
        val fetch: suspend (RecordingMockServer) -> MergeRequest,
    )

    @Test
    fun `every platform delivers diff content for changed files`() =
        runTest {
            val failures = cases().mapNotNull { case ->
                val server = RecordingMockServer(case.routes)
                val outcome = runCatching { case.fetch(server) }
                val mr = outcome.getOrNull()
                when {
                    mr == null -> {
                        "${case.name}: fetch failed: ${outcome.exceptionOrNull()}"
                    }

                    mr.changes.none { it.path == FILE && it.diffContent?.contains(DIFF_SECRET_LINE) == true } -> {
                        "${case.name}: changes=${mr.changes.map { "${it.path} diff=${it.diffContent != null}" }}"
                    }

                    else -> {
                        null
                    }
                }
            }

            assertEquals(emptyList<String>(), failures, "platforms without diff content for $FILE")
        }

    private fun cases(): List<PlatformCase> =
        listOf(
            PlatformCase("bitbucket", bitbucketRoutes()) { server ->
                val service = initialized(BitbucketPlatformService(server.engine), "token" to "t")
                service.fetchMergeRequest("ws/demo", "7")
            },
            PlatformCase("azuredevops", azureRoutes()) { server ->
                val service = initialized(AzureDevOpsPlatformService(server.engine), "token" to "t")
                service.fetchMergeRequest("org/proj/demo", "7")
            },
            PlatformCase("gitea", giteaRoutes()) { server ->
                val service = initialized(GiteaPlatformService(server.engine), "url" to GITEA_URL, "token" to "t")
                service.fetchMergeRequest("o/demo", "7")
            },
            PlatformCase("gerrit", gerritRoutes()) { server ->
                val service = initialized(GerritPlatformService(server.engine), "url" to GERRIT_URL, "token" to "t")
                service.fetchMergeRequest("demo", "7")
            },
            PlatformCase("awscodecommit", awsRoutes()) { server ->
                val service = initialized(
                    AwsCodeCommitPlatformService(server.engine),
                    "region" to "us-east-1",
                    "accessKeyId" to "AKIDEXAMPLE",
                    "secretAccessKey" to "secret"
                )
                service.fetchMergeRequest("demo", "7")
            },
        )

    private fun bitbucketRoutes() =
        listOf(
            pathEndsWith("/pullrequests/7/diff", GIT_DIFF, TEXT),
            pathEndsWith(
                "/pullrequests/7/diffstat",
                """
                {"pagelen": 500, "page": 1, "size": 1, "values": [{
                  "type": "diffstat", "status": "modified", "lines_added": 1, "lines_removed": 0,
                  "old": {"path": "$FILE", "type": "commit_file", "escaped_path": "$FILE"},
                  "new": {"path": "$FILE", "type": "commit_file", "escaped_path": "$FILE"}
                }]}
                """.trimIndent(),
            ),
            pathEndsWith(
                "/pullrequests/7/commits",
                """
                {"pagelen": 50, "values": [{"hash": "$HEAD", "date": "$DATE", "message": "add key",
                  "author": {"raw": "Dev <dev@example.com>"}}]}
                """.trimIndent(),
            ),
            pathEndsWith(
                "/pullrequests/7",
                """
                {"id": 7, "title": "Add key", "description": "", "state": "OPEN",
                 "author": {"uuid": "{u1}", "display_name": "Dev", "nickname": "dev"},
                 "source": {"branch": {"name": "feature"}, "commit": {"hash": "$HEAD"},
                            "repository": {"uuid": "{r1}", "name": "demo", "full_name": "ws/demo"}},
                 "destination": {"branch": {"name": "main"}, "commit": {"hash": "$BASE"},
                                 "repository": {"uuid": "{r1}", "name": "demo", "full_name": "ws/demo"}},
                 "created_on": "$DATE", "updated_on": "$DATE", "participants": [], "reviewers": []}
                """.trimIndent(),
            ),
        )

    private fun azureRoutes(): List<MockRoute> {
        fun lowerPathEndsWith(
            suffix: String,
            body: String,
        ) = MockRoute({
            it.url.encodedPath
                .lowercase()
                .endsWith(suffix.lowercase())
        }, body)
        return listOf(
            MockRoute({ isAzureContent(it, HEAD, "head-blob") }, HEAD_FILE, TEXT),
            MockRoute({ isAzureContent(it, BASE, "base-blob") }, BASE_FILE, TEXT),
            lowerPathEndsWith(
                "/pullRequests/7/iterations/1/changes",
                """
                {"changeEntries": [{"changeTrackingId": 1, "changeId": 1, "changeType": "edit",
                  "item": {"objectId": "head-blob", "originalObjectId": "base-blob", "path": "/$FILE"}}]}
                """.trimIndent(),
            ),
            lowerPathEndsWith(
                "/pullRequests/7/iterations",
                """
                {"count": 1, "value": [{"id": 1, "sourceRefCommit": {"commitId": "$HEAD"},
                  "targetRefCommit": {"commitId": "$BASE"}, "commonRefCommit": {"commitId": "$BASE"}}]}
                """.trimIndent(),
            ),
            lowerPathEndsWith(
                "/pullRequests/7/commits",
                """
                {"count": 1, "value": [{"commitId": "$HEAD", "comment": "add key",
                  "author": {"name": "Dev", "email": "dev@example.com", "date": "$DATE"},
                  "committer": {"name": "Dev", "email": "dev@example.com", "date": "$DATE"}}]}
                """.trimIndent(),
            ),
            lowerPathEndsWith(
                "/pullRequests/7",
                """
                {"pullRequestId": 7, "title": "Add key", "description": "", "status": "active",
                 "createdBy": {"id": "u1", "displayName": "Dev", "uniqueName": "dev@example.com"},
                 "creationDate": "$DATE", "sourceRefName": "refs/heads/feature", "targetRefName": "refs/heads/main",
                 "isDraft": false, "reviewers": [], "labels": [],
                 "lastMergeSourceCommit": {"commitId": "$HEAD"}, "lastMergeTargetCommit": {"commitId": "$BASE"}}
                """.trimIndent(),
            ),
        )
    }

    /** Azure file content: `/items?path=…&versionDescriptor.version=<sha>` or `/blobs/<objectId>`. */
    private fun isAzureContent(
        request: HttpRequestData,
        sha: String,
        blobId: String,
    ): Boolean {
        val path = request.url.encodedPath
        return path.endsWith("/blobs/$blobId") ||
            (path.endsWith("/items") && request.url.parameters["versionDescriptor.version"] == sha)
    }

    private fun giteaRoutes() =
        listOf(
            pathEndsWith("/pulls/7.diff", GIT_DIFF, TEXT),
            pathEndsWith("/pulls/7.patch", GIT_DIFF, TEXT),
            pathEndsWith(
                "/pulls/7/files",
                """[{"filename": "$FILE", "status": "changed", "additions": 1, "deletions": 0, "changes": 1}]""",
            ),
            pathEndsWith(
                "/pulls/7/commits",
                """
                [{"sha": "$HEAD", "commit": {"message": "add key",
                  "author": {"name": "Dev", "email": "dev@example.com", "date": "$DATE"},
                  "committer": {"name": "Dev", "email": "dev@example.com", "date": "$DATE"}}}]
                """.trimIndent(),
            ),
            pathEndsWith("/pulls/7/reviews", "[]"),
            pathEndsWith(
                "/pulls/7",
                """
                {"id": 70, "number": 7, "title": "Add key", "body": "", "html_url": "$GITEA_URL/o/demo/pulls/7",
                 "state": "open", "user": {"id": 1, "login": "dev"},
                 "head": {"ref": "feature", "sha": "$HEAD"}, "base": {"ref": "main", "sha": "$BASE"},
                 "labels": [], "draft": false, "merged": false, "created_at": "$DATE", "updated_at": "$DATE"}
                """.trimIndent(),
            ),
        )

    private fun gerritRoutes(): List<MockRoute> {
        val diffInfo =
            """
            {"meta_a": {"name": "$FILE", "content_type": "text/x-kotlin", "lines": 1},
             "meta_b": {"name": "$FILE", "content_type": "text/x-kotlin", "lines": 2},
             "change_type": "MODIFIED", "diff_header": [],
             "content": [{"ab": ["package demo"]}, {"b": [${JsonPrimitive(DIFF_SECRET_LINE)}]}]}
            """.trimIndent()
        return listOf(
            MockRoute({ it.url.encodedPath.endsWith("/revisions/$HEAD/patch") }, Base64.encode(GIT_DIFF.encodeToByteArray()), TEXT),
            MockRoute({ "/revisions/$HEAD/files/" in it.url.encodedPath && it.url.encodedPath.endsWith("/diff") }, XSSI + diffInfo),
            MockRoute({ "/changes/" in it.url.encodedPath && "/revisions/" !in it.url.encodedPath }, XSSI + GERRIT_CHANGE),
        )
    }

    private fun awsRoutes(): List<MockRoute> {
        fun blob(
            blobId: String,
            content: String,
        ) = MockRoute(
            { it.headers[AMZ_TARGET] == "CodeCommit_20150413.GetBlob" && blobId in requestText(it) },
            """{"content": "${Base64.encode(content.encodeToByteArray())}"}""",
            "application/x-amz-json-1.1",
        )
        return listOf(
            blob("blob-head", HEAD_FILE),
            blob("blob-base", BASE_FILE),
            amzTarget(
                "CodeCommit_20150413.GetPullRequest",
                """
                {"pullRequest": {"pullRequestId": "7", "title": "Add key", "description": "",
                  "pullRequestStatus": "OPEN", "authorArn": "arn:aws:iam::123456789012:user/dev",
                  "creationDate": "$DATE", "revisionId": "rev-1",
                  "pullRequestTargets": [{"repositoryName": "demo", "sourceReference": "refs/heads/feature",
                    "destinationReference": "refs/heads/main", "sourceCommit": "$HEAD", "destinationCommit": "$BASE"}]}}
                """.trimIndent(),
            ),
            amzTarget(
                "CodeCommit_20150413.GetDifferences",
                """
                {"differences": [{"changeType": "M",
                  "beforeBlob": {"blobId": "blob-base", "path": "$FILE", "mode": "100644"},
                  "afterBlob": {"blobId": "blob-head", "path": "$FILE", "mode": "100644"}}]}
                """.trimIndent(),
            ),
            amzTarget(
                "CodeCommit_20150413.GetCommit",
                """
                {"commit": {"commitId": "$HEAD", "treeId": "t1", "parents": ["$BASE"], "message": "add key",
                  "author": {"name": "Dev", "email": "dev@example.com", "date": "1700000000 +0000"},
                  "committer": {"name": "Dev", "email": "dev@example.com", "date": "1700000000 +0000"}}}
                """.trimIndent(),
            ),
            // Any other operation (approvals, and the commit listing tracked by the AWS and Gerrit API contract) answers an empty object
            // so that this test isolates the diff gap.
            MockRoute({ true }, "{}", "application/x-amz-json-1.1"),
        )
    }

    private fun requestText(request: HttpRequestData): String = (request.body as? TextContent)?.text.orEmpty()

    private companion object {
        const val FILE = "src/Config.kt"
        const val HEAD = "2222222222222222222222222222222222222222"
        const val BASE = "1111111111111111111111111111111111111111"
        const val DATE = "2026-10-01T10:00:00Z"
        const val TEXT = "text/plain"
        const val XSSI = ")]}'\n"
        const val GITEA_URL = "https://gitea.example.com"
        const val GERRIT_URL = "https://gerrit.example.com"
        const val BASE_FILE = "package demo\n"
        val HEAD_FILE = "package demo\n$DIFF_SECRET_LINE\n"

        val GIT_DIFF =
            "diff --git a/$FILE b/$FILE\n" +
                "index 1111111..2222222 100644\n" +
                "--- a/$FILE\n" +
                "+++ b/$FILE\n" +
                UNIFIED_DIFF_HUNK + "\n"

        /**
         * Change detail as Gerrit returns it for `o=CURRENT_REVISION&o=CURRENT_COMMIT&o=CURRENT_FILES`.
         * The nested commit carries a `commit` key that real Gerrit omits there, because `GerritCommitInfo.commit`
         * is non-null; that separate decoding bug is out of scope for this contract.
         */
        val GERRIT_CHANGE =
            """
            {"id": "demo~main~I1", "_number": 7, "change_id": "I1", "subject": "Add key", "project": "demo",
             "branch": "main", "status": "NEW", "owner": {"_account_id": 1000, "name": "Dev", "username": "dev"},
             "current_revision": "$HEAD",
             "revisions": {"$HEAD": {"kind": "REWORK", "_number": 1, "created": "2026-10-01 10:00:00.000000000",
               "uploader": {"_account_id": 1000}, "ref": "refs/changes/07/7/1",
               "commit": {"commit": "$HEAD", "parents": [{"commit": "$BASE", "subject": "init"}],
                 "author": {"name": "Dev", "email": "dev@example.com", "date": "2026-10-01 10:00:00.000000000"},
                 "committer": {"name": "Dev", "email": "dev@example.com", "date": "2026-10-01 10:00:00.000000000"},
                 "subject": "Add key", "message": "Add key\n\nChange-Id: I1\n"},
               "files": {"$FILE": {"lines_inserted": 1, "lines_deleted": 0, "size_delta": 40, "size": 60}}}}}
            """.trimIndent()
    }
}
