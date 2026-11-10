package io.karandash.loader

import kotlin.test.Test
import kotlin.test.assertEquals

class LoadFailureMessageTest {
    @Test
    fun `a load failure renders with the PLUGIN_LOAD_FAILED code`() {
        val failure = LoadResult.LoadFailure(path = "plugins/broken.jar", reason = "no manifest")

        assertEquals("[E0300] Failed to load plugin 'plugins/broken.jar': no manifest", failure.message)
    }
}
