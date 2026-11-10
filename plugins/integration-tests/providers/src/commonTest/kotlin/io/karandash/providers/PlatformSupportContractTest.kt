package io.karandash.providers

import io.karandash.api.PluginContext
import io.karandash.api.module.PlatformServiceModule
import io.karandash.api.platform.InlineCommentBatchPoster
import io.karandash.api.platform.PlatformOperation
import io.karandash.api.platform.ResultStatusPublisher
import io.karandash.api.platform.UnsupportedPlatformOperationException
import io.karandash.logger.Logger
import io.karandash.logger.NoOpLogger
import io.karandash.models.DiffRefs
import io.karandash.platforms.awscodecommit.AwsCodeCommitPlatformService
import io.karandash.platforms.azuredevops.AzureDevOpsPlatformService
import io.karandash.platforms.bitbucket.BitbucketPlatformService
import io.karandash.platforms.gerrit.GerritPlatformService
import io.karandash.platforms.gitea.GiteaPlatformService
import io.karandash.platforms.github.GitHubPlatformService
import io.karandash.platforms.gitlab.GitLabPlatformService
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * `supports(op)` is the truth: an operation a platform declares never fails with
 * [UnsupportedPlatformOperationException], and one it does not declare fails with it without a single HTTP
 * request. Every service runs over a [MockEngine] answering 404, so
 * a declared operation fails for other reasons and no call leaves the test.
 */
class PlatformSupportContractTest {
    private class Subject(
        val name: String,
        val config: JsonElement,
        val create: (HttpClientEngine) -> PlatformServiceModule,
    )

    private val subjects = listOf(
        Subject("gitlab", HOSTED) { GitLabPlatformService(it) },
        Subject("github", HOSTED) { GitHubPlatformService(it) },
        Subject("bitbucket", HOSTED) { BitbucketPlatformService(it) },
        Subject("gerrit", HOSTED) { GerritPlatformService(it) },
        Subject("azure-devops", HOSTED) { AzureDevOpsPlatformService(it) },
        Subject("gitea", HOSTED) { GiteaPlatformService(it) },
        Subject("aws", AWS) { AwsCodeCommitPlatformService(it) },
    )

    @Test
    fun `an operation a platform does not declare fails as unsupported without a request`() =
        runTest {
            subjects.forEach { subject ->
                val requests = mutableListOf<String>()
                val module = initialized(subject.config, subject.create(engine(requests)))
                val undeclared = CALLABLE.filterNot { module.supports(it) }
                requests.clear() // supports() may probe the server version (Gitea); the ops themselves must not call
                undeclared.forEach { op ->
                    assertUnsupported(module, op, subject.name)
                    assertEquals(emptyList(), requests, "${subject.name}: undeclared $op made requests")
                }
                module.shutdown()
            }
        }

    @Test
    fun `an operation a platform declares never fails as unsupported`() =
        runTest {
            subjects.forEach { subject ->
                val module = initialized(subject.config, subject.create(engine(mutableListOf())))
                CALLABLE.filter { module.supports(it) }.forEach { op ->
                    val failure = invoke(module, op).exceptionOrNull()
                    if (failure is UnsupportedPlatformOperationException) {
                        fail("${subject.name}: declared $op failed as unsupported: ${failure.message}")
                    }
                }
                module.shutdown()
            }
        }

    @Test
    fun `BATCH_INLINE is declared exactly by the batch posters`() =
        runTest {
            subjects.forEach { subject ->
                val module = initialized(subject.config, subject.create(engine(mutableListOf())))
                assertEquals(
                    module is InlineCommentBatchPoster,
                    module.supports(PlatformOperation.BATCH_INLINE),
                    "${subject.name}: BATCH_INLINE",
                )
            }
        }

    @Test
    fun `RESULT_STATUS is declared exactly by the result status publishers`() =
        runTest {
            subjects.forEach { subject ->
                val module = initialized(subject.config, subject.create(engine(mutableListOf())))
                assertEquals(
                    module is ResultStatusPublisher,
                    module.supports(PlatformOperation.RESULT_STATUS),
                    "${subject.name}: RESULT_STATUS",
                )
                module.shutdown()
            }
        }

    @Test
    fun `operations the platform API does not offer stay declared unsupported`() =
        runTest {
            subjects.forEach { subject ->
                val module = initialized(subject.config, subject.create(engine(mutableListOf())))
                DECLARED_UNSUPPORTED[subject.name].orEmpty().forEach { (op, reason) ->
                    assertFalse(module.supports(op), "${subject.name}: $op is declared unsupported ($reason)")
                }
                module.shutdown()
            }
        }

    @Test
    fun `a platform without a membership API cannot tell project membership and asks nothing`() =
        runTest {
            subjects.filter { it.name in NO_MEMBERSHIP_API }.forEach { subject ->
                val requests = mutableListOf<String>()
                val module = initialized(subject.config, subject.create(engine(requests)))
                assertNull(module.isProjectMember(P, "u"), "${subject.name}: ${NO_MEMBERSHIP_API[subject.name]}")
                assertTrue(requests.isEmpty(), "${subject.name}: $requests")
                module.shutdown()
            }
        }

    @Test
    fun `a platform without SUGGESTIONS renders the plain diff fallback`() =
        runTest {
            subjects.forEach { subject ->
                val module = initialized(subject.config, subject.create(engine(mutableListOf())))
                val block = module.renderSuggestionBlock("val x = 1")
                val native = block.startsWith("```suggestion")
                assertEquals(module.supports(PlatformOperation.SUGGESTIONS), native, "${subject.name}: $block")
                if (!native) assertTrue(block.startsWith("```diff"), "${subject.name}: $block")
                module.shutdown()
            }
        }

    private suspend fun initialized(
        config: JsonElement,
        module: PlatformServiceModule,
    ): PlatformServiceModule {
        assertTrue(module.initialize(context(config)), "${module.id}: initialize")
        return module
    }

    private suspend fun assertUnsupported(
        module: PlatformServiceModule,
        op: PlatformOperation,
        name: String,
    ) {
        val result = invoke(module, op)
        assertFalse(result.isSuccess, "$name: undeclared $op succeeded")
        val failure = result.exceptionOrNull()
        assertTrue(failure is UnsupportedPlatformOperationException, "$name: undeclared $op failed with $failure")
    }

    /** Calls the SDK method behind [op]; a `Result`-returning method's failure is unwrapped into the outer result. */
    private suspend fun invoke(
        module: PlatformServiceModule,
        op: PlatformOperation,
    ): Result<Any?> =
        runCatching {
            when (op) {
                PlatformOperation.MERGE -> module.mergeMr(P, M).getOrThrow()
                PlatformOperation.MANAGE_REVIEWERS -> module.addReviewers(P, M, listOf("u")).getOrThrow()
                PlatformOperation.POST_INLINE_COMMENTS -> module.postInlineComment(P, M, "b", "a.kt", 1, DiffRefs("b", "h"))
                PlatformOperation.RESOLVE_DISCUSSIONS -> module.resolveThread(P, M, "1").getOrThrow()
                PlatformOperation.LABELS -> module.addLabels(P, M, listOf("l"))
                PlatformOperation.DRAFT -> module.setDraft(P, M, true)
                PlatformOperation.APPROVE -> module.approve(P, M)
                PlatformOperation.COMMENTS -> module.postComment(P, M, "b")
                PlatformOperation.REOPEN -> module.reopenMr(P, M).getOrThrow()
                PlatformOperation.REVIEW_THREADS -> module.replyToThread(P, M, "1", "b").getOrThrow()
                PlatformOperation.FILE_CONTENT -> module.fetchFileContent(P, "main", "a.kt")
                PlatformOperation.SEARCH -> module.searchMergeRequests(P, "t", null, 1)
                PlatformOperation.LIST_OPEN -> module.listOpenMergeRequestIds(P, "main", 1)
                PlatformOperation.DISMISSED_INLINE -> module.listDismissedInlineComments(P, M, "m")
                PlatformOperation.APPROVAL_SIGNALS -> module.fetchApprovalSignals(P, M)
                else -> error("$op has no callable SDK method")
            }
        }

    private fun engine(requests: MutableList<String>) =
        MockEngine { request ->
            requests += "${request.method.value} ${request.url}"
            respond("{}", HttpStatusCode.NotFound)
        }

    private fun context(config: JsonElement): PluginContext =
        object : PluginContext {
            override val pluginConfig: JsonElement = config
            override val logger: Logger = NoOpLogger
            override val hostVersion: String = "test"
            override val workingDirectory: String = "/tmp"

            override fun env(name: String): String? = null

            override fun warn(message: String) {}

            override fun log(message: String) {}
        }

    private companion object {
        const val P = "team/app"
        const val M = "7"

        /**
         * Operations with one SDK method to call. Not here: the rendering flags INLINE_RANGES and SUGGESTIONS, and
         * BATCH_INLINE and RESULT_STATUS (checked by type).
         */
        val CALLABLE = listOf(
            PlatformOperation.MERGE,
            PlatformOperation.MANAGE_REVIEWERS,
            PlatformOperation.POST_INLINE_COMMENTS,
            PlatformOperation.RESOLVE_DISCUSSIONS,
            PlatformOperation.LABELS,
            PlatformOperation.DRAFT,
            PlatformOperation.APPROVE,
            PlatformOperation.COMMENTS,
            PlatformOperation.REOPEN,
            PlatformOperation.REVIEW_THREADS,
            PlatformOperation.FILE_CONTENT,
            PlatformOperation.SEARCH,
            PlatformOperation.LIST_OPEN,
            PlatformOperation.DISMISSED_INLINE,
            PlatformOperation.APPROVAL_SIGNALS,
        )

        /** Operations the platform's own API does not offer, with the reason; `supports` must keep saying no. */
        val DECLARED_UNSUPPORTED: Map<String, Map<PlatformOperation, String>> = mapOf(
            "bitbucket" to mapOf(
                PlatformOperation.REOPEN to "Bitbucket Cloud cannot reopen a declined pull request",
                PlatformOperation.SUGGESTIONS to "Bitbucket Cloud suggestions are a UI feature with no documented markup",
            ),
            "gerrit" to mapOf(
                PlatformOperation.RESULT_STATUS to "core Gerrit has no commit status API; checks come from an optional plugin",
            ),
            "gitea" to mapOf(PlatformOperation.INLINE_RANGES to "review comments take a single new_position line"),
            "aws" to mapOf(
                PlatformOperation.INLINE_RANGES to "PostCommentForPullRequest Location takes a single filePosition",
                PlatformOperation.LABELS to "CodeCommit pull requests have no labels",
                PlatformOperation.DRAFT to "CodeCommit pull requests have no draft state",
                PlatformOperation.MANAGE_REVIEWERS to "reviewers come only from approval rule templates",
                PlatformOperation.RESOLVE_DISCUSSIONS to "CodeCommit comments cannot be resolved",
                PlatformOperation.RESULT_STATUS to "CodeCommit has no commit status or check API",
            ),
        )

        /** Platforms whose `isProjectMember` stays the `null` default, with the reason. */
        val NO_MEMBERSHIP_API: Map<String, String> = mapOf(
            "gerrit" to "project access lists need the View Access capability, which a review bot rarely has",
            "azure-devops" to "membership lives in the Graph API on vssps, behind descriptors and group expansion",
            "aws" to "CodeCommit access is IAM policy, not a member list",
        )

        val HOSTED = JsonObject(mapOf("url" to JsonPrimitive("https://scm.example.com"), "token" to JsonPrimitive("t0ken")))
        val AWS = JsonObject(
            mapOf(
                "region" to JsonPrimitive("us-east-1"),
                "accessKeyId" to JsonPrimitive("AKIDEXAMPLE"),
                "secretAccessKey" to JsonPrimitive("secret"),
            ),
        )
    }
}
