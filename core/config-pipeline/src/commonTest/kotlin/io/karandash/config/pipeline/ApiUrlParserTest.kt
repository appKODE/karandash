package io.karandash.config.pipeline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class ApiUrlParserTest {
    // ── GitLab parsing ──────────────────────────────────────────────────

    @Test
    fun `tryParse recognizes GitLab files API URL`() {
        val url = "https://gitlab.com/api/v4/projects/123/repository/files/path%2Fto%2Fconfig.yaml/raw?ref=main"
        val parsed = ApiUrlParser.tryParse(url)
        assertIs<ParsedApiUrl.GitLabFileUrl>(parsed)
        assertEquals("https://gitlab.com", parsed.origin)
        assertEquals("path/to/config.yaml", parsed.repoFilePath)
        assertEquals(mapOf("ref" to "main"), parsed.queryParams)
    }

    @Test
    fun `tryParse recognizes self-hosted GitLab with subpath`() {
        val url = "https://gitlab.example.com/api/v4/projects/1234/repository/files/base-kmp%2F.karandash%2Fconfig.yaml/raw?ref=main"
        val parsed = ApiUrlParser.tryParse(url)
        assertIs<ParsedApiUrl.GitLabFileUrl>(parsed)
        assertEquals("https://gitlab.example.com", parsed.origin)
        assertEquals("base-kmp/.karandash/config.yaml", parsed.repoFilePath)
        assertEquals(mapOf("ref" to "main"), parsed.queryParams)
    }

    @Test
    fun `tryParse preserves multiple query params`() {
        val url = "https://gitlab.com/api/v4/projects/42/repository/files/config.yaml/raw?ref=develop&private_token=abc"
        val parsed = ApiUrlParser.tryParse(url)
        assertIs<ParsedApiUrl.GitLabFileUrl>(parsed)
        assertEquals(mapOf("ref" to "develop", "private_token" to "abc"), parsed.queryParams)
    }

    @Test
    fun `tryParse handles GitLab URL without query params`() {
        val url = "https://gitlab.com/api/v4/projects/1/repository/files/config.yaml/raw"
        val parsed = ApiUrlParser.tryParse(url)
        assertIs<ParsedApiUrl.GitLabFileUrl>(parsed)
        assertEquals(emptyMap(), parsed.queryParams)
        assertEquals("config.yaml", parsed.repoFilePath)
    }

    @Test
    fun `tryParse returns null for non-API URL`() {
        assertNull(ApiUrlParser.tryParse("https://example.com/configs/config.yaml"))
        assertNull(ApiUrlParser.tryParse("https://raw.githubusercontent.com/owner/repo/main/config.yaml"))
        assertNull(ApiUrlParser.tryParse("/local/path/config.yaml"))
        assertNull(ApiUrlParser.tryParse("../relative/config.yaml"))
    }

    // ── GitLab resolve ──────────────────────────────────────────────────

    @Test
    fun `resolve relative path with parent traversal produces correct GitLab API URL`() {
        val url = "https://gitlab.example.com/api/v4/projects/1234/repository/files/base-kmp%2F.karandash%2Fconfig.yaml/raw?ref=main"
        val parsed = ApiUrlParser.tryParse(url)
        assertIs<ParsedApiUrl.GitLabFileUrl>(parsed)

        // "../base/.karandash/config.yaml" from "base-kmp/.karandash/" goes up to "base-kmp/"
        val resolved = parsed.resolve("../base/.karandash/config.yaml")
        assertEquals(
            "https://gitlab.example.com/api/v4/projects/1234/repository/files/base-kmp%2Fbase%2F.karandash%2Fconfig.yaml/raw?ref=main",
            resolved,
        )
    }

    @Test
    fun `resolve with two parent traversals exits nested directory`() {
        val url = "https://gitlab.example.com/api/v4/projects/1234/repository/files/base-kmp%2F.karandash%2Fconfig.yaml/raw?ref=main"
        val parsed = ApiUrlParser.tryParse(url)
        assertIs<ParsedApiUrl.GitLabFileUrl>(parsed)

        // "../../base/.karandash/config.yaml" from "base-kmp/.karandash/" goes up to repo root
        val resolved = parsed.resolve("../../base/.karandash/config.yaml")
        assertEquals(
            "https://gitlab.example.com/api/v4/projects/1234/repository/files/base%2F.karandash%2Fconfig.yaml/raw?ref=main",
            resolved,
        )
    }

    @Test
    fun `resolve sibling file in same directory`() {
        val url = "https://gitlab.com/api/v4/projects/1/repository/files/configs%2Fbase.yaml/raw?ref=main"
        val parsed = ApiUrlParser.tryParse(url)
        assertIs<ParsedApiUrl.GitLabFileUrl>(parsed)

        val resolved = parsed.resolve("sibling.yaml")
        assertEquals(
            "https://gitlab.com/api/v4/projects/1/repository/files/configs%2Fsibling.yaml/raw?ref=main",
            resolved,
        )
    }

    @Test
    fun `resolve with dot-slash prefix`() {
        val url = "https://gitlab.com/api/v4/projects/1/repository/files/dir%2Fconfig.yaml/raw?ref=main"
        val parsed = ApiUrlParser.tryParse(url)
        assertIs<ParsedApiUrl.GitLabFileUrl>(parsed)

        val resolved = parsed.resolve("./other.yaml")
        assertEquals(
            "https://gitlab.com/api/v4/projects/1/repository/files/dir%2Fother.yaml/raw?ref=main",
            resolved,
        )
    }

    @Test
    fun `resolve subdirectory path`() {
        val url = "https://gitlab.com/api/v4/projects/1/repository/files/base%2Fconfig.yaml/raw?ref=main"
        val parsed = ApiUrlParser.tryParse(url)
        assertIs<ParsedApiUrl.GitLabFileUrl>(parsed)

        val resolved = parsed.resolve("sub/child.yaml")
        assertEquals(
            "https://gitlab.com/api/v4/projects/1/repository/files/base%2Fsub%2Fchild.yaml/raw?ref=main",
            resolved,
        )
    }

    @Test
    fun `resolve multiple parent traversals`() {
        val url = "https://gitlab.com/api/v4/projects/1/repository/files/a%2Fb%2Fc%2Fconfig.yaml/raw?ref=main"
        val parsed = ApiUrlParser.tryParse(url)
        assertIs<ParsedApiUrl.GitLabFileUrl>(parsed)

        val resolved = parsed.resolve("../../other/config.yaml")
        assertEquals(
            "https://gitlab.com/api/v4/projects/1/repository/files/a%2Fother%2Fconfig.yaml/raw?ref=main",
            resolved,
        )
    }

    @Test
    fun `resolve file at repo root`() {
        val url = "https://gitlab.com/api/v4/projects/1/repository/files/config.yaml/raw?ref=main"
        val parsed = ApiUrlParser.tryParse(url)
        assertIs<ParsedApiUrl.GitLabFileUrl>(parsed)

        val resolved = parsed.resolve("other.yaml")
        assertEquals(
            "https://gitlab.com/api/v4/projects/1/repository/files/other.yaml/raw?ref=main",
            resolved,
        )
    }

    @Test
    fun `parentDirUrl returns directory API URL`() {
        val url = "https://gitlab.com/api/v4/projects/1/repository/files/configs%2Fbase.yaml/raw?ref=main"
        val parsed = ApiUrlParser.tryParse(url)
        assertIs<ParsedApiUrl.GitLabFileUrl>(parsed)

        val parentUrl = parsed.parentDirUrl()
        assertEquals(
            "https://gitlab.com/api/v4/projects/1/repository/files/configs/raw?ref=main",
            parentUrl,
        )
    }

    // ── GitHub parsing ──────────────────────────────────────────────────

    @Test
    fun `tryParse recognizes GitHub contents API URL`() {
        val url = "https://api.github.com/repos/owner/repo/contents/path/to/config.yaml?ref=main"
        val parsed = ApiUrlParser.tryParse(url)
        assertIs<ParsedApiUrl.GitHubContentsUrl>(parsed)
        assertEquals("https://api.github.com", parsed.origin)
        assertEquals("path/to/config.yaml", parsed.repoFilePath)
        assertEquals(mapOf("ref" to "main"), parsed.queryParams)
    }

    @Test
    fun `GitHub resolve relative path`() {
        val url = "https://api.github.com/repos/owner/repo/contents/configs/base.yaml?ref=main"
        val parsed = ApiUrlParser.tryParse(url)
        assertIs<ParsedApiUrl.GitHubContentsUrl>(parsed)

        val resolved = parsed.resolve("../other/config.yaml")
        assertEquals(
            "https://api.github.com/repos/owner/repo/contents/other/config.yaml?ref=main",
            resolved,
        )
    }

    // ── URL encoding utilities ──────────────────────────────────────────

    @Test
    fun `urlEncodePath encodes slashes and special characters`() {
        assertEquals("base%2Fconfig.yaml", urlEncodePath("base/config.yaml"))
        assertEquals("path%2Fwith%20space.yaml", urlEncodePath("path/with space.yaml"))
    }

    @Test
    fun `urlDecodePath decodes percent-encoded characters`() {
        assertEquals("base/config.yaml", urlDecodePath("base%2Fconfig.yaml"))
        assertEquals("path/with space.yaml", urlDecodePath("path%2Fwith%20space.yaml"))
    }

    @Test
    fun `resolveRepoPath handles parent traversal`() {
        // "../base/..." from "base-kmp/.karandash/config.yaml" -> parent is "base-kmp/.karandash"
        // so ".." goes to "base-kmp", then "base/..." gives "base-kmp/base/.karandash/config.yaml"
        assertEquals(
            "base-kmp/base/.karandash/config.yaml",
            resolveRepoPath("base-kmp/.karandash/config.yaml", "../base/.karandash/config.yaml")
        )
        // "../../base/..." goes up to repo root
        assertEquals("base/.karandash/config.yaml", resolveRepoPath("base-kmp/.karandash/config.yaml", "../../base/.karandash/config.yaml"))
        assertEquals("other.yaml", resolveRepoPath("config.yaml", "other.yaml"))
        assertEquals("a/other/config.yaml", resolveRepoPath("a/b/c/config.yaml", "../../other/config.yaml"))
    }

    @Test
    fun `parseQueryString parses key-value pairs`() {
        assertEquals(mapOf("ref" to "main"), parseQueryString("?ref=main"))
        assertEquals(mapOf("ref" to "main", "token" to "abc"), parseQueryString("?ref=main&token=abc"))
        assertEquals(emptyMap(), parseQueryString(""))
        assertEquals(emptyMap(), parseQueryString("?"))
    }

    @Test
    fun `buildQueryString reconstructs query string`() {
        assertEquals("", buildQueryString(emptyMap()))
        assertEquals("?ref=main", buildQueryString(mapOf("ref" to "main")))
    }
}
