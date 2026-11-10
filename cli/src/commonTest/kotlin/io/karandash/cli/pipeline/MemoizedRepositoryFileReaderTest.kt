@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.cli.pipeline

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MemoizedRepositoryFileReaderTest {
    @Test
    fun `each file is fetched once per run across concurrent rules and misses`() =
        runTest {
            val fetched = mutableListOf<String>()
            val reader = MemoizedRepositoryFileReader { _, path ->
                fetched += path
                yield()
                "text".takeIf { path == "a.kt" }
            }

            val reads = List(5) { async { reader.read("sha", "a.kt") to reader.read("sha", "missing.kt") } }.awaitAll()

            assertEquals(List(5) { "text" to null }, reads)
            assertEquals(listOf("a.kt", "missing.kt"), fetched)
            assertNull(reader.read("sha", "missing.kt"))
        }
}
