package io.karandash.config.pipeline

import io.karandash.platform.PathSecurity
import kotlinx.serialization.Serializable

/**
 * Parses Git platform API URLs (GitLab, GitHub) and resolves relative paths
 * within the logical repository file tree, reconstructing valid API URLs.
 *
 * This fixes chained `extends:` resolution when a URL-fetched config contains
 * relative path references like `../base/.karandash/config.yaml`. Without this,
 * `getParentDirectoryOf()` treats the URL-encoded file path as a single segment
 * and produces invalid API URLs.
 */
object ApiUrlParser {
    private const val GITLAB_FILES_SEGMENT = "/repository/files/"
    private const val GITLAB_RAW_SUFFIX = "/raw"
    private const val GITHUB_CONTENTS_SEGMENT = "/contents/"

    /**
     * Attempts to parse [url] as a known Git platform API URL.
     *
     * @return A [ParsedApiUrl] if the URL matches a known pattern, or `null` to fall back
     *   to the default (naive) resolution.
     */
    fun tryParse(url: String): ParsedApiUrl? = tryParseGitLab(url) ?: tryParseGitHub(url)

    private fun tryParseGitLab(url: String): ParsedApiUrl.GitLabFileUrl? {
        val filesIdx = url.indexOf(GITLAB_FILES_SEGMENT)
        if (filesIdx < 0) return null

        val afterFiles = filesIdx + GITLAB_FILES_SEGMENT.length
        val remaining = url.substring(afterFiles)

        val rawIdx = remaining.indexOf(GITLAB_RAW_SUFFIX)
        if (rawIdx < 0) return null

        val encodedPath = remaining.substring(0, rawIdx)
        if (encodedPath.isEmpty()) return null

        val afterRaw = remaining.substring(rawIdx + GITLAB_RAW_SUFFIX.length)
        val queryParams = parseQueryString(afterRaw)

        val apiBase = url.substring(0, filesIdx)
        val repoFilePath = urlDecodePath(encodedPath)

        return ParsedApiUrl.GitLabFileUrl(
            origin = PathSecurity.extractOrigin(url),
            apiBase = apiBase,
            repoFilePath = repoFilePath,
            queryParams = queryParams,
        )
    }

    private fun tryParseGitHub(url: String): ParsedApiUrl.GitHubContentsUrl? {
        val contentsIdx = url.indexOf(GITHUB_CONTENTS_SEGMENT)
        if (contentsIdx < 0) return null

        val apiPrefix = url.substring(0, contentsIdx)
        if (!apiPrefix.contains("/repos/")) return null

        val afterContents = contentsIdx + GITHUB_CONTENTS_SEGMENT.length
        val remaining = url.substring(afterContents)

        val queryStart = remaining.indexOf('?')
        val repoFilePath = if (queryStart >= 0) remaining.substring(0, queryStart) else remaining
        if (repoFilePath.isEmpty()) return null

        val queryParams = if (queryStart >= 0) {
            parseQueryString(remaining.substring(queryStart))
        } else {
            emptyMap()
        }

        return ParsedApiUrl.GitHubContentsUrl(
            origin = PathSecurity.extractOrigin(url),
            apiBase = apiPrefix,
            repoFilePath = repoFilePath,
            queryParams = queryParams,
        )
    }
}

/**
 * Represents a parsed Git platform API URL with the ability to resolve
 * relative paths within the repository file tree and reconstruct valid API URLs.
 */
sealed class ParsedApiUrl {
    abstract val origin: String
    abstract val repoFilePath: String
    abstract val queryParams: Map<String, String>

    /** Resolves [relativePath] against this file's repo location and returns a full API URL. */
    abstract fun resolve(relativePath: String): String

    /**
     * Returns an API URL pointing to this file's parent directory.
     * Used by [ExternalValueResolver] for `{ file: "..." }` resolution in remote configs.
     */
    abstract fun parentDirUrl(): String

    @Serializable
    data class GitLabFileUrl(
        override val origin: String,
        val apiBase: String,
        override val repoFilePath: String,
        override val queryParams: Map<String, String>,
    ) : ParsedApiUrl() {
        override fun resolve(relativePath: String): String {
            val resolvedPath = resolveRepoPath(repoFilePath, relativePath)
            val encoded = urlEncodePath(resolvedPath)
            val query = buildQueryString(queryParams)
            return "$apiBase/repository/files/$encoded/raw$query"
        }

        override fun parentDirUrl(): String {
            val parentDir = repoFileParentDir(repoFilePath)
            val encoded = urlEncodePath(parentDir)
            val query = buildQueryString(queryParams)
            return "$apiBase/repository/files/$encoded/raw$query"
        }
    }

    @Serializable
    data class GitHubContentsUrl(
        override val origin: String,
        val apiBase: String,
        override val repoFilePath: String,
        override val queryParams: Map<String, String>,
    ) : ParsedApiUrl() {
        override fun resolve(relativePath: String): String {
            val resolvedPath = resolveRepoPath(repoFilePath, relativePath)
            val query = buildQueryString(queryParams)
            return "$apiBase/contents/$resolvedPath$query"
        }

        override fun parentDirUrl(): String {
            val parentDir = repoFileParentDir(repoFilePath)
            val query = buildQueryString(queryParams)
            return "$apiBase/contents/$parentDir$query"
        }
    }
}

// ── URL encoding / path utilities (private to this file) ────────────────

internal fun urlEncodePath(path: String): String =
    path
        .replace("%", "%25")
        .replace("/", "%2F")
        .replace(" ", "%20")
        .replace("#", "%23")
        .replace("?", "%3F")

internal fun urlDecodePath(encoded: String): String {
    val result = StringBuilder(encoded.length)
    var i = 0
    while (i < encoded.length) {
        if (encoded[i] == '%' && i + 2 < encoded.length) {
            val hex = encoded.substring(i + 1, i + 3)
            val decoded = hex.toIntOrNull(radix = 16)
            if (decoded != null) {
                result.append(decoded.toChar())
                i += 3
                continue
            }
        }
        result.append(encoded[i])
        i++
    }
    return result.toString()
}

internal fun parseQueryString(raw: String): Map<String, String> {
    val query = raw.removePrefix("?")
    if (query.isBlank()) return emptyMap()
    return query
        .split("&")
        .mapNotNull { param ->
            val eqIdx = param.indexOf('=')
            if (eqIdx > 0) param.substring(0, eqIdx) to param.substring(eqIdx + 1) else null
        }.toMap()
}

internal fun buildQueryString(params: Map<String, String>): String {
    if (params.isEmpty()) return ""
    return "?" + params.entries.joinToString("&") { (k, v) -> "$k=$v" }
}

/**
 * Resolves a relative path against a base repo file path.
 * Handles `..` and `.` segments on the logical repository file tree.
 */
internal fun resolveRepoPath(
    basePath: String,
    relativePath: String
): String {
    val baseDir = repoFileParentDir(basePath)
    val combined = if (baseDir.isEmpty()) relativePath else "$baseDir/$relativePath"
    val segments = combined.split("/")
    val result = mutableListOf<String>()
    for (seg in segments) {
        when (seg) {
            ".", "" -> {}

            ".." -> {
                if (result.isNotEmpty()) result.removeLast()
            }

            else -> {
                result.add(seg)
            }
        }
    }
    return result.joinToString("/")
}

private fun repoFileParentDir(filePath: String): String {
    val lastSlash = filePath.lastIndexOf('/')
    return if (lastSlash > 0) filePath.substring(0, lastSlash) else ""
}
