package io.karandash.api.platform

import kotlinx.serialization.Serializable

/**
 * Options for merging a merge request.
 *
 * Passed to [PlatformServiceModule.mergeMr][io.karandash.api.module.PlatformServiceModule.mergeMr]
 * to control merge behavior.
 */
@Serializable
public data class MergeOptions(
    val squash: Boolean = false,
    val deleteSourceBranch: Boolean = false,
    val commitMessage: String? = null,
)
