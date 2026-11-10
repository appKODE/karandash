package io.karandash.cli.commands.wizard

import io.karandash.config.keys.ModuleIds
import io.karandash.profiles.MinimalProfileModule
import kotlin.test.Test
import kotlin.test.assertEquals

class WizardProfileIdTest {
    @Test
    fun `wizard default profile is the minimal profile module id`() {
        assertEquals(MinimalProfileModule.PROFILE_ID, ModuleIds.MINIMAL_PROFILE)
        assertEquals(MinimalProfileModule.PROFILE_ID, WizardContext(detection = null, platform = "github", outputPath = "out").profileId)
    }
}
