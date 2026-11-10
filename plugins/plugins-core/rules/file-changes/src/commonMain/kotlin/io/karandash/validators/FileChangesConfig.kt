package io.karandash.validators

import io.karandash.api.RuleConfig
import io.karandash.sdk.config.BlockMode
import io.karandash.sdk.config.BlockModeFalseOffSerializer
import kotlinx.serialization.Serializable

/**
 * `fileChanges` rule config.
 *
 * Name overlaps (see docs/rules/file-changes.md): [requireApproval] passes when any one listed user approved,
 * unlike `approval.requiredApprovers` (every listed user) and the boolean `ai.inlineOverrides.requireApproval`;
 * [blockOnDraft] is separate from `draft.blockDraft`. Each key is read only by its own rule.
 */
@Serializable
data class FileChangesConfig(
    val patterns: List<FilePatternRule> = emptyList(),
    val requireApproval: List<String>? = null,
    val requireLabels: List<String>? = null,
    /** `fail | warn | off`; `true` = fail, `false` = off. */
    @Serializable(with = BlockModeFalseOffSerializer::class)
    val blockOnDraft: BlockMode = BlockMode.OFF,
    val blockedPaths: List<String> = emptyList(),
    val requiredPaths: List<String> = emptyList(),
    val requireCoverage: Boolean = false
) : RuleConfig

@Serializable
data class FilePatternRule(
    val pattern: String,
    val requireApproval: List<String>? = null,
    val requireLabels: List<String>? = null,
    /** `fail | warn | off`; `true` = fail, `false` = off. */
    @Serializable(with = BlockModeFalseOffSerializer::class)
    val blockOnDraft: BlockMode = BlockMode.OFF,
    /** When true, the MR needs an approval from someone other than its author for a matching file. */
    val requireNonAuthorApproval: Boolean = false,
)
