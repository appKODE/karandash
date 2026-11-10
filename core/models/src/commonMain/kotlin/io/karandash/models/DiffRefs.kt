package io.karandash.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Diff reference SHAs used for positioning inline comments on merge request diffs.
 *
 * Platform-neutral: GitLab has `diff_refs`, GitHub has `base.sha`/`head.sha`,
 * Bitbucket has `source.commit.hash`/`destination.commit.hash`, etc.
 *
 * @param baseSha The base (target branch) commit SHA.
 * @param headSha The head (source branch) commit SHA.
 * @param startSha Optional start SHA for three-way diffs (GitLab). Defaults to [baseSha] if absent.
 */
@Serializable
data class DiffRefs(
    @SerialName("base_sha") val baseSha: String,
    @SerialName("head_sha") val headSha: String,
    @SerialName("start_sha") val startSha: String? = null,
)
