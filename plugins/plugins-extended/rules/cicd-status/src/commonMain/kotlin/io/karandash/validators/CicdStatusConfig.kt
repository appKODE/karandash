package io.karandash.validators

import io.karandash.api.RuleConfig
import io.karandash.sdk.config.BlockMode
import io.karandash.sdk.config.BlockModeFalseWarnSerializer
import kotlinx.serialization.Serializable

/**
 * @param blockOnFailing `fail | warn | off`; `true` = fail, `false` = warn. `fail`: failures are errors. `warn`:
 *   failures found by [requirePassed]/[requiredJobs] are warnings and the failing-pipeline check that runs without
 *   [requirePassed] is skipped (today's `false`). `off`: no failure finding at all.
 */
@Serializable
data class CicdStatusConfig(
    val requirePassed: Boolean = true,
    val requiredJobs: List<String> = emptyList(),
    @Serializable(with = BlockModeFalseWarnSerializer::class)
    val blockOnFailing: BlockMode = BlockMode.FAIL,
) : RuleConfig
