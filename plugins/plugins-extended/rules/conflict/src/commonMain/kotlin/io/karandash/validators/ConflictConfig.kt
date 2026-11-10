package io.karandash.validators

import io.karandash.api.RuleConfig
import io.karandash.sdk.config.BlockMode
import io.karandash.sdk.config.BlockModeFalseOffSerializer
import kotlinx.serialization.Serializable

/**
 * @param blockOnConflicts `fail | warn | off`; `true` = fail, `false` = off (the rule reports nothing, like
 * `enabled: false`).
 */
@Serializable
data class ConflictConfig(
    @Serializable(with = BlockModeFalseOffSerializer::class)
    val blockOnConflicts: BlockMode = BlockMode.FAIL,
) : RuleConfig
