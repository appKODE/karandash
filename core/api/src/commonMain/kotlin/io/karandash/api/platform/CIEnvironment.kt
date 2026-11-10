package io.karandash.api.platform

import kotlinx.serialization.Serializable

@Serializable
public data class CIEnvironment(
    val platform: String,
    val projectId: String?,
    val mrId: String?,
    val token: String?,
    val baseUrl: String?
) {
    public companion object {
        private val PULL_REF = Regex("""^refs/pull/(\d+)/""")

        /**
         * The pull request number of a `GITHUB_REF`-style [ref]: `refs/pull/N/merge` (GitHub Actions) and
         * `refs/pull/N/head` (Gitea and Forgejo Actions, GitHub workflows checking out the head ref); null for any other ref.
         */
        public fun pullRequestNumber(ref: String?): String? = ref?.let { PULL_REF.find(it)?.groupValues?.get(1) }
    }
}
