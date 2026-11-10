package io.karandash.cache

import io.karandash.logger.Logger
import io.karandash.testfixtures.TestFiles
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertSame

/**
 * A re-entrant call during cache creation makes the outer CAS attempt lose, as a concurrent thread would.
 */
class CacheManagerRaceTest {
    private class ReentrantLogger : Logger {
        var onFirstDebug: (() -> Unit)? = null

        override val isDebugEnabled: Boolean get() = true

        override fun debug(message: String) {
            onFirstDebug?.let {
                onFirstDebug = null
                it()
            }
        }

        override fun info(message: String) = Unit

        override fun warn(message: String) = Unit

        override fun error(message: String) = Unit
    }

    @Test
    fun `getOrCreate returns the cache that is in the map after a lost CAS`() {
        val logger = ReentrantLogger()
        val manager = CacheManager(CacheConfig(), TestFiles.createTestTempDirectory("karandash-race-cache"), logger)
        val spec = CacheSpec<String, String>(maxEntries = 10)
        var inner: TieredCache<String, String>? = null
        logger.onFirstDebug = { inner = manager.getOrCreate("race", spec) }

        val outer = manager.getOrCreate("race", spec)

        assertSame(inner, outer)
        assertSame(outer, manager.getOrCreate("race", spec))
    }
}
