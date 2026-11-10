package io.karandash.platforms.github

import io.karandash.testfixtures.TestFiles
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** `GITHUB_EVENT_PATH` supplies the PR number when `GITHUB_REF` is not `refs/pull/N/merge`. */
class GitHubEventPayloadTest {
    private fun eventFile(json: String): Path {
        val file = Path(TestFiles.createTestTempDirectory("gh-event"), "gh-event.json")
        SystemFileSystem.sink(file).buffered().use { it.writeString(json) }
        return file
    }

    private fun ciEnv(eventPath: String): (String) -> String? =
        mapOf(
            "GITHUB_ACTIONS" to "true",
            "GITHUB_REPOSITORY" to "acme/app",
            "GITHUB_REF" to "refs/heads/main",
            GitHubEventPayload.ENV_VAR to eventPath,
        )::get

    @Test
    fun `pull_request number from the event payload becomes the CI mrId`() =
        runTest {
            val file = eventFile("""{"action":"opened","pull_request":{"number":42,"title":"x"}}""")
            try {
                val ci = GitHubPlatformService().detectCiEnvironment(ciEnv(file.toString()))
                assertEquals("42", ci?.mrId)
            } finally {
                SystemFileSystem.delete(file, mustExist = false)
            }
        }

    @Test
    fun `top-level number is used when the payload has no pull_request object`() =
        runTest {
            val file = eventFile("""{"action":"submitted","number":7}""")
            try {
                val ci = GitHubPlatformService().detectCiEnvironment(ciEnv(file.toString()))
                assertEquals("7", ci?.mrId)
            } finally {
                SystemFileSystem.delete(file, mustExist = false)
            }
        }

    @Test
    fun `GITHUB_REF pull ref wins over the event payload`() =
        runTest {
            val file = eventFile("""{"pull_request":{"number":42}}""")
            try {
                val env = mapOf(
                    "GITHUB_ACTIONS" to "true",
                    "GITHUB_REF" to "refs/pull/9/merge",
                    GitHubEventPayload.ENV_VAR to file.toString(),
                )
                assertEquals("9", GitHubPlatformService().detectCiEnvironment(env::get)?.mrId)
            } finally {
                SystemFileSystem.delete(file, mustExist = false)
            }
        }

    @Test
    fun `missing or malformed event payload leaves mrId null`() =
        runTest {
            val missing = Path(TestFiles.createTestTempDirectory("gh-event-missing"), "gh-event-missing.json")
            assertNull(GitHubPlatformService().detectCiEnvironment(ciEnv(missing.toString()))?.mrId)
            val bad = eventFile("not json")
            try {
                assertNull(GitHubPlatformService().detectCiEnvironment(ciEnv(bad.toString()))?.mrId)
            } finally {
                SystemFileSystem.delete(bad, mustExist = false)
            }
        }
}
