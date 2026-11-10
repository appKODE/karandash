@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.cli.pipeline

import io.karandash.api.finding.ReviewThread
import io.karandash.api.run.MergeRequestThreads
import io.karandash.logger.NoOpLogger
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReadOnlyMergeRequestThreadsTest {
    private class WritingThreads : MergeRequestThreads {
        val writes = mutableListOf<String>()

        override suspend fun list(): List<ReviewThread> = emptyList()

        override suspend fun isMember(username: String) = username == "member"

        override suspend fun resolve(
            threadId: String,
            note: String,
        ): Result<Unit> = Result.success(Unit).also { writes += "resolve $threadId" }

        override suspend fun reply(
            threadId: String,
            body: String,
        ): Result<Unit> = Result.success(Unit).also { writes += "reply $threadId" }
    }

    @Test
    fun `resolve and reply reach no platform while reads pass through`() =
        runTest {
            val inner = WritingThreads()
            val threads = ReadOnlyMergeRequestThreads(inner, NoOpLogger)

            assertTrue(threads.resolve("t1", "note").isSuccess)
            assertTrue(threads.reply("t2", "body").isSuccess)

            assertEquals(emptyList(), inner.writes)
            assertEquals(emptyList(), threads.list())
            assertTrue(threads.isMember("member"))
        }
}
