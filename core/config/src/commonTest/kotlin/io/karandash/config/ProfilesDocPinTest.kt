package io.karandash.config

import io.karandash.testfixtures.TestFiles
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class ProfilesDocPinTest {
    /** Pin: docs/features/profiles.md exists. */
    @Test
    fun `profiles doc exists`() {
        val root = checkNotNull(TestFiles.findRepoRoot()) { "repo root not found" }
        assertTrue(TestFiles.exists(Path(root, "docs/features/profiles.md")))
    }
}
