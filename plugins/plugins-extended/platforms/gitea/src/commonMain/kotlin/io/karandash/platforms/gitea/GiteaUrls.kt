package io.karandash.platforms.gitea

import io.karandash.api.platform.ParsedMergeRequestUrl

/** Gitea pull request and git remote URL parsing for [GiteaPlatformService]. */
internal object GiteaUrls {
    private const val GITHUB_HOST = "github"
    private const val GITLAB_HOST = "gitlab"
    private const val BITBUCKET_HOST = "bitbucket"
    private const val AZURE_HOST = "azure"

    private val URL_REGEX = Regex("""(?:https?://)?[^/]+/([^/]+)/([^/]+)/pulls/(\d+)""")
    private val PARSE_MR_URL_REGEX = Regex("""https?://([^/]+)/([^/]+)/([^/]+)/pulls/(\d+)""")
    private val GIT_SSH_REMOTE_REGEX = Regex("""git@([^:]+):([^/]+/[^/]+?)(?:\.git)?$""")
    private val GIT_HTTPS_REMOTE_REGEX = Regex("""https?://([^/]+)/([^/]+/[^/]+?)(?:\.git)?$""")

    fun ownerRepoId(
        owner: String,
        repo: String,
    ) = "$owner/$repo"

    /** Owner, repo and PR number of a `.../{owner}/{repo}/pulls/{id}` URL. */
    fun parsePullRequestUrl(url: String): Triple<String, String, String> {
        val match = URL_REGEX.find(url)
            ?: throw IllegalArgumentException(
                "Invalid Gitea URL format: $url. Expected format: https://gitea.com/{owner}/{repo}/pulls/{id}",
            )
        return Triple(match.groupValues[1], match.groupValues[2], match.groupValues[3])
    }

    /** [url] as a merge request of [platform], or null when it is no PR URL or points at GitHub. */
    fun parseMergeRequestUrl(
        platform: String,
        url: String,
    ): ParsedMergeRequestUrl? {
        val match = PARSE_MR_URL_REGEX.find(url) ?: return null
        val host = match.groupValues[1]
        if (host.contains(GITHUB_HOST, ignoreCase = true)) return null
        val owner = match.groupValues[2]
        val repo = match.groupValues[3]
        val prId = match.groupValues[4]
        return ParsedMergeRequestUrl(platform = platform, baseUrl = "https://$host", projectId = ownerRepoId(owner, repo), mrId = prId)
    }

    /** `owner/repo` of a git remote URL on a host that is not one of the other known platforms, else null. */
    fun parseGitRemoteUrl(url: String): String? {
        val sshMatch = GIT_SSH_REMOTE_REGEX.find(url)
        if (sshMatch != null) {
            val host = sshMatch.groupValues[1]
            if (!host.contains(GITHUB_HOST) &&
                !host.contains(GITLAB_HOST) &&
                !host.contains(BITBUCKET_HOST) &&
                !host.contains(AZURE_HOST)
            ) {
                return sshMatch.groupValues[2]
            }
        }
        val httpsMatch = GIT_HTTPS_REMOTE_REGEX.find(url)
        if (httpsMatch != null) {
            val host = httpsMatch.groupValues[1]
            if (!host.contains(GITHUB_HOST) &&
                !host.contains(GITLAB_HOST) &&
                !host.contains(BITBUCKET_HOST) &&
                !host.contains(AZURE_HOST) &&
                !host.contains("amazonaws")
            ) {
                return httpsMatch.groupValues[2]
            }
        }
        return null
    }
}
