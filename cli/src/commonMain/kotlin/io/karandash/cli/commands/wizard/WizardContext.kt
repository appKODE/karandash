package io.karandash.cli.commands.wizard

import io.karandash.config.keys.ModuleIds
import io.karandash.detect.DetectionResult
import kotlinx.serialization.Serializable

@Serializable
data class WizardContext(
    val detection: DetectionResult?,
    val platform: String,
    val profileId: String = ModuleIds.MINIMAL_PROFILE,
    val outputPath: String,
)
