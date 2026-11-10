package io.karandash.cli.commands.validate

import io.karandash.api.ModuleRegistryImpl
import io.karandash.api.PluginContext
import io.karandash.api.errors.ConfigException
import io.karandash.api.errors.KarandashError
import io.karandash.api.errors.ProviderException
import io.karandash.api.exception.ProviderApiException
import io.karandash.api.module.PlatformServiceModule
import io.karandash.api.platform.ParsedMergeRequestUrl
import io.karandash.cli.provider.ProviderResolver
import io.karandash.cli.provider.ProviderRuntime
import io.karandash.models.DiffRefs
import io.karandash.models.MergeRequest
import io.karandash.sdk.NoOpLogger
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Mode conflicts, file mode errors and the user-facing message for every provider fetch failure. */
class ValidateModeResolverErrorsTest {
    /** A provider whose fetch always throws [failure]; the hint methods return fixed text; [authAccepted] is `checkAuth`. */
    private class FailingHub(
        private val failure: Exception,
        private val authAccepted: Boolean? = null,
    ) : PlatformServiceModule {
        override val id: String = "failhub"
        override val name: String = "FailHub"
        override val version: String = "1.0"

        override suspend fun initialize(context: PluginContext): Boolean = true

        override suspend fun authTokenHint(): String = "FAILHUB_TOKEN"

        override fun permissionScope(): String = "needs read_api"

        override fun tokenLocationHint(): String = "token lives in env"

        override suspend fun checkAuth(): Boolean? = authAccepted

        override suspend fun addLabels(
            projectId: String,
            mrId: String,
            labels: List<String>
        ) {}

        override suspend fun removeLabels(
            projectId: String,
            mrId: String,
            labels: List<String>
        ) {}

        override suspend fun setDraft(
            projectId: String,
            mrId: String,
            draft: Boolean
        ) {}

        override suspend fun updateTitle(
            projectId: String,
            mrId: String,
            title: String
        ) {}

        override suspend fun updateDescription(
            projectId: String,
            mrId: String,
            description: String
        ) {}

        override suspend fun approve(
            projectId: String,
            mrId: String
        ) {}

        override suspend fun unapprove(
            projectId: String,
            mrId: String
        ) {}

        override suspend fun parseMergeRequestUrl(url: String): ParsedMergeRequestUrl? = null

        override suspend fun fetchMergeRequestByUrl(url: String): MergeRequest = throw failure

        override suspend fun fetchMergeRequest(
            projectId: String,
            mrId: String,
        ): MergeRequest = throw failure

        override suspend fun postComment(
            projectId: String,
            mrId: String,
            body: String,
        ) = ""

        override suspend fun updateComment(
            projectId: String,
            mrId: String,
            commentId: String,
            body: String,
        ) {}

        override suspend fun findExistingComment(
            projectId: String,
            mrId: String,
            marker: String,
        ): String? = null

        override suspend fun postInlineComment(
            projectId: String,
            mrId: String,
            body: String,
            filePath: String,
            line: Int,
            diffRefs: DiffRefs,
            startLine: Int?,
        ) = ""
    }

    private fun resolver(
        mrDataPath: String? = null,
        mrId: String? = null,
        platform: String? = null,
        project: String? = null,
        prUrl: String? = null,
        fileContent: String = "{}",
    ) = ValidateModeResolver(
        mrDataPath = mrDataPath,
        mrId = mrId,
        platform = platform,
        project = project,
        prUrl = prUrl,
        noAutoDetect = true,
        logger = NoOpLogger,
        readMrFileContent = { fileContent },
        providerRuntime = ProviderRuntime(ProviderResolver()),
    )

    private suspend fun configError(resolver: ValidateModeResolver): String =
        assertFailsWith<ConfigException> { resolver.resolve(ModuleRegistryImpl(), emptySet()) }.message.orEmpty()

    private suspend fun liveFailure(
        failure: Exception,
        authAccepted: Boolean? = null,
    ): String {
        val registry = ModuleRegistryImpl().apply { register(FailingHub(failure, authAccepted)) }
        val resolver = resolver(mrId = "7", platform = "failhub", project = "o/r")
        return assertFailsWith<ProviderException> { resolver.resolve(registry, emptySet()) }.message.orEmpty()
    }

    private fun apiError(status: Int) = ProviderApiException(status, "Status", "failhub", "GET /mr", "body-$status")

    @Test
    fun `conflicting or incomplete modes are config errors`() =
        runTest {
            assertTrue("Choose one mode" in configError(resolver(mrDataPath = "mr.json", mrId = "1")))
            assertTrue("Missing: --platform, --project" in configError(resolver(mrId = "1")))
            assertTrue("Must specify either --mr" in configError(resolver()))
            assertTrue("together with --mr (file mode)" in configError(resolver(prUrl = "https://x/1", mrDataPath = "m")))
            assertTrue("--pr alone" in configError(resolver(prUrl = "https://x/1", project = "p")))
            assertTrue("Cannot parse PR URL" in configError(resolver(prUrl = "https://x/1")))
        }

    @Test
    fun `an unreadable mr file is a config error`() =
        runTest {
            val message = configError(resolver(mrDataPath = "mr.json", fileContent = "not json"))
            assertTrue(message.startsWith("Failed to load MR data from file"), message)
        }

    @Test
    fun `http 401 403 and 404 explain the failure with the provider hints`() =
        runTest {
            val unauthorized = liveFailure(apiError(401))
            assertTrue("Authentication failed for failhub provider (HTTP 401)" in unauthorized, unauthorized)
            assertTrue("Server response: body-401" in unauthorized, unauthorized)
            assertTrue("Hint: configure token via FAILHUB_TOKEN" in unauthorized, unauthorized)
            assertTrue("token lives in env" in unauthorized, unauthorized)

            val forbidden = liveFailure(apiError(403))
            assertTrue("Insufficient permissions" in forbidden && "needs read_api" in forbidden, forbidden)

            val notFound = liveFailure(apiError(404))
            assertTrue("Resource not found on failhub (HTTP 404)" in notFound, notFound)
            assertTrue("Verify the project/MR ID is correct" in notFound, notFound)
        }

    @Test
    fun `a 404 with credentials the platform rejects reports the missing token`() =
        runTest {
            val missing = liveFailure(apiError(404), authAccepted = false)
            assertTrue(missing.startsWith("[E0100] failhub answered HTTP 404 and does not accept the configured token"), missing)
            assertTrue("Hint: configure token via FAILHUB_TOKEN" in missing && "token lives in env" in missing, missing)

            assertTrue(liveFailure(apiError(404), authAccepted = true).startsWith("[E0103] "))
        }

    @Test
    fun `a failure without a message names its type`() =
        runTest {
            assertEquals("Failed to fetch MR from failhub: UnsupportedOperationException", liveFailure(UnsupportedOperationException()))
        }

    @Test
    fun `rate limits and other http errors name the status`() =
        runTest {
            assertTrue("API rate limit exceeded" in liveFailure(apiError(429)))
            val other = liveFailure(apiError(500))
            assertTrue("failhub API error (HTTP 500 Status)" in other && "Request: GET /mr" in other, other)
        }

    @Test
    fun `wrapped api errors auth text and other failures become provider errors`() =
        runTest {
            val wrapped = liveFailure(IllegalStateException("outer", apiError(403)))
            assertTrue("HTTP 403" in wrapped, wrapped)

            val auth = liveFailure(IllegalArgumentException("401 Unauthorized"))
            assertEquals("${KarandashError.AUTH_FAILED.bracketCode} 401 Unauthorized\nHint: configure token via FAILHUB_TOKEN.", auth)

            val other = liveFailure(UnsupportedOperationException("boom"))
            assertEquals("Failed to fetch MR from failhub: boom", other)
        }

    @Test
    fun `provider failures carry their catalog codes`() =
        runTest {
            for (status in listOf(401, 403)) {
                assertTrue(liveFailure(apiError(status)).startsWith("[E0101] "), "HTTP $status")
            }
            assertTrue(liveFailure(apiError(404)).startsWith("[E0103] "))
            assertTrue(liveFailure(apiError(429)).startsWith("[E0104] "))
            assertTrue(liveFailure(IllegalArgumentException("401 Unauthorized")).startsWith("[E0101] "))
            assertTrue(
                liveFailure(IllegalStateException("${KarandashError.AUTH_MISSING.bracketCode} X token not found.")).startsWith("[E0100] "),
            )
            assertTrue(configError(resolver(prUrl = "https://x/1")).startsWith("[E0012] "))
        }
}
