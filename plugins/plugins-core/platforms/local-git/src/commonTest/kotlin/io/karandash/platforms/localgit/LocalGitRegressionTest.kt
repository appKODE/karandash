package io.karandash.platforms.localgit

import kotlin.test.Test
import kotlin.test.assertEquals

/** local-git default target branch. */
class LocalGitRegressionTest {
    @Test
    fun `origin HEAD wins over main`() {
        assertEquals("origin/develop", pickDefaultBranch("origin/develop") { true })
    }

    @Test
    fun `master is used when main is missing`() {
        assertEquals("master", pickDefaultBranch(null) { it == "master" })
    }

    @Test
    fun `main is the last resort`() {
        assertEquals("main", pickDefaultBranch("") { false })
    }
}
