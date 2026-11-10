package io.karandash.contracts.fw

import io.karandash.config.schema.ConfigSections
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The documented top-level `plugins:` block must be a known config section, or it is silently dropped.
 */
class ConfigPluginsBlockContractTest {
    @Test
    fun `plugins block is a known config section`() {
        assertTrue(
            "plugins" in ConfigSections.all,
            "'plugins' missing from ConfigSections.all=${ConfigSections.all}",
        )
    }
}
