package io.karandash.cli.commands.mr

import com.github.ajalt.clikt.testing.test
import io.karandash.api.PluginContext
import io.karandash.api.platform.MergeOptions
import io.karandash.api.platform.ParsedMergeRequestUrl
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cli.TestFileHelpers
import io.karandash.cli.errors.ExitCodes
import io.karandash.models.DiffRefs
import io.karandash.sdk.typed.AbstractTypedPlatformServiceModule
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/** `mr merge|close|reopen` resolve the platform from the URL and call it with its `platforms:` config. */
class MrCommandTest {
    private val tempDir = TestFileHelpers.createTempDir("karandash-mr")
    private val configFile =
        Path(tempDir, "config.yaml")
            .also {
                TestFileHelpers.writeFile(it, "platforms:\n  fakeplat:\n    token: from-config\n")
            }.toString()

    @AfterTest
    fun cleanup() {
        TestFileHelpers.deleteTempDir(tempDir)
    }

    private class FakePlatform(
        private val fail: Boolean = false,
    ) : AbstractTypedPlatformServiceModule<JsonObject>(JsonObject.serializer()) {
        val calls = mutableListOf<String>()

        override val id = "fakeplat"
        override val name = "Fake"
        override val version = "1.0.0"

        override suspend fun initializeTyped(
            context: PluginContext,
            config: JsonObject,
        ): Boolean {
            calls += "init ${config["token"]?.jsonPrimitive?.content}"
            return true
        }

        override suspend fun shutdown() {
            calls += "shutdown"
        }

        override suspend fun parseMergeRequestUrl(url: String): ParsedMergeRequestUrl? =
            if (url.startsWith(
                    URL_PREFIX
                )
            ) {
                ParsedMergeRequestUrl(id, "https://fake.example", "group/repo", url.substringAfterLast('/'))
            } else {
                null
            }

        private fun record(call: String): Result<Unit> {
            calls += call
            return if (fail) Result.failure(IllegalStateException("conflict")) else Result.success(Unit)
        }

        override suspend fun mergeMr(
            projectId: String,
            mrId: String,
            options: MergeOptions,
        ) = record("merge $projectId $mrId $options")

        override suspend fun closeMr(
            projectId: String,
            mrId: String,
        ) = record("close $projectId $mrId")

        override suspend fun reopenMr(
            projectId: String,
            mrId: String,
        ) = record("reopen $projectId $mrId")

        override suspend fun postComment(
            projectId: String,
            mrId: String,
            body: String,
        ): String = ""

        override suspend fun updateComment(
            projectId: String,
            mrId: String,
            commentId: String,
            body: String,
        ) = Unit

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
        ): String = ""
    }

    private suspend fun bootstrapWith(platform: FakePlatform): PluginBootstrap {
        val bootstrap = PluginBootstrap()
        bootstrap.loadPlugins()
        bootstrap.registry().register(platform)
        return bootstrap
    }

    @Test
    fun `merge passes the options and the platforms config block`() =
        runTest {
            val platform = FakePlatform()
            val command = MrMergeCommand(bootstrapWith(platform))
            val result = command.test(listOf("$URL_PREFIX/7", "--squash", "--delete-source-branch", "-m", "msg", "--config", configFile))

            assertEquals(0, result.statusCode, result.stderr)
            assertContains(result.output, "Merged $URL_PREFIX/7")
            val options = MergeOptions(squash = true, deleteSourceBranch = true, commitMessage = "msg")
            assertEquals(listOf("init from-config", "merge group/repo 7 $options", "shutdown"), platform.calls)
        }

    @Test
    fun `close and reopen call the platform`() =
        runTest {
            val platform = FakePlatform()
            val bootstrap = bootstrapWith(platform)
            assertEquals(0, MrCloseCommand(bootstrap).test(listOf("$URL_PREFIX/3", "--config", configFile)).statusCode)
            assertEquals(0, MrReopenCommand(bootstrap).test(listOf("$URL_PREFIX/3", "--config", configFile)).statusCode)
            assertEquals(
                listOf("close group/repo 3", "reopen group/repo 3"),
                platform.calls.filter {
                    it.startsWith("close") ||
                        it.startsWith("reopen")
                }
            )
        }

    @Test
    fun `a platform failure exits with the provider error code`() =
        runTest {
            val platform = FakePlatform(fail = true)
            val result = MrCloseCommand(bootstrapWith(platform)).test(listOf("$URL_PREFIX/3", "--config", configFile))

            assertEquals(ExitCodes.PROVIDER_ERROR, result.statusCode)
            assertContains(result.stderr, "conflict")
            assertEquals("shutdown", platform.calls.last())
        }

    @Test
    fun `an unknown URL exits with the config error code`() =
        runTest {
            val result = MrMergeCommand(bootstrapWith(FakePlatform())).test(listOf("https://nowhere.example/x", "--config", configFile))

            assertEquals(ExitCodes.CONFIG_ERROR, result.statusCode)
            assertContains(result.stderr, "Cannot parse MR")
        }

    private companion object {
        const val URL_PREFIX = "https://fake.example/review"
    }
}
