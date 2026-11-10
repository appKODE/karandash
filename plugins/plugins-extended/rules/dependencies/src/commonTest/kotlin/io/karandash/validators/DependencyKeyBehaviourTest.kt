package io.karandash.validators

import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.testfixtures.createTestMergeRequest
import kotlin.test.Test
import kotlin.test.assertEquals

class DependencyKeyBehaviourTest {
    private fun count(
        config: DependencyValidationConfig,
        entry: String,
    ): Int {
        val diff = "--- a/package.json\n+++ b/package.json\n@@ -1,2 +1,3 @@\n {\n+  $entry\n }"
        val mr = createTestMergeRequest(changes = listOf(FileChange("package.json", ChangeType.MODIFIED, diffContent = diff)))
        return DependencyValidator(config).validate(mr).size
    }

    @Test
    fun `warnOnUnpinnedVersions flags a version range`() {
        assertEquals(1, count(DependencyValidationConfig(warnOnUnpinnedVersions = true), "\"lodash\": \"^4.17.21\""))
        assertEquals(0, count(DependencyValidationConfig(warnOnUnpinnedVersions = false), "\"lodash\": \"^4.17.21\""))
    }

    @Test
    fun `minVersions flags a version below the minimum`() {
        val config = DependencyValidationConfig(warnOnUnpinnedVersions = false, minVersions = mapOf("lodash" to "4.17.21"))
        assertEquals(1, count(config, "\"lodash\": \"4.0.0\""))
        assertEquals(0, count(config, "\"lodash\": \"4.17.21\""))
    }

    @Test
    fun `blockedPatterns flags a matching dependency`() {
        val config = DependencyValidationConfig(warnOnUnpinnedVersions = false, blockedPatterns = listOf("left-pad"))
        assertEquals(1, count(config, "\"left-pad\": \"1.0.0\""))
        assertEquals(0, count(config, "\"lodash\": \"1.0.0\""))
    }
}
