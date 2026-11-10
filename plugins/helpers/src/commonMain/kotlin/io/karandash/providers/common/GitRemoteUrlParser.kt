package io.karandash.providers.common

/**
 * Shared utility for parsing git remote URLs into platform project IDs.
 *
 * Both SSH (`git@host:owner/repo.git`) and HTTPS (`https://host/owner/repo.git`) formats are
 * supported. Each platform service supplies its own compiled regexes - this function applies them
 * uniformly and returns the first capturing group on a match.
 */
object GitRemoteUrlParser {
    /**
     * Attempts to extract a project ID from a git remote URL.
     *
     * Tries [sshRegex] first, then [httpsRegex]. Returns the value of capture group 1 on the
     * first match, or null if neither regex matches.
     *
     * @param url The raw remote URL (SSH or HTTPS format).
     * @param sshRegex Pre-compiled regex for SSH remote URLs; capture group 1 must be the project ID.
     * @param httpsRegex Pre-compiled regex for HTTPS remote URLs; capture group 1 must be the project ID.
     * @return The matched project ID, or null if the URL is not recognised.
     */
    fun parse(
        url: String,
        sshRegex: Regex,
        httpsRegex: Regex,
    ): String? {
        val sshMatch = sshRegex.find(url)
        if (sshMatch != null) return sshMatch.groupValues[1]
        return httpsRegex.find(url)?.groupValues?.get(1)
    }
}
