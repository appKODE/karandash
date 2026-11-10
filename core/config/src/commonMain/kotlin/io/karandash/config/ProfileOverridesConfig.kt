package io.karandash.config

import kotlinx.serialization.Serializable

/**
 * Overrides for title format rule thresholds when applied via a profile.
 *
 * Non-null fields are merged into the corresponding rule config element
 * after profile resolution.
 */
@Serializable
data class TitleOverridesConfig(
    val minLength: Int? = null,
    val maxLength: Int? = null,
    val maxSubjectLength: Int? = null,
)

/**
 * Overrides for size limits rule thresholds when applied via a profile.
 */
@Serializable
data class SizeOverridesConfig(
    val maxLines: Int? = null,
    val maxCommits: Int? = null,
)

/**
 * Top-level config block for profile threshold overrides.
 *
 * Allows users to tweak profile-contributed rule thresholds without
 * fully overriding the rule config. Non-null fields are merged into
 * the respective rule configs after profile resolution.
 *
 * ```yaml
 * profile-overrides:
 *   title:
 *     min-length: 10
 *     max-length: 72
 *   size:
 *     max-lines: 800
 * ```
 */
@Serializable
data class ProfileOverridesConfig(
    val title: TitleOverridesConfig = TitleOverridesConfig(),
    val size: SizeOverridesConfig = SizeOverridesConfig(),
)
