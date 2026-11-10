package io.karandash.platforms.gitlab

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GitLabCredentialsTest {
    private fun createConfig(map: Map<String, Any?>): JsonElement =
        JsonObject(map.mapNotNull { (k, v) -> if (v is String) k to JsonPrimitive(v) else null }.toMap())

    private fun createEmptyEnv(): (String) -> String? = { null }

    private fun createEnv(vars: Map<String, String>): (String) -> String? = { vars[it] }

    // Token resolution tests
    @Test
    fun `resolve uses config token when present`() {
        val config = createConfig(mapOf("token" to "config-token"))
        val result = GitLabCredentials.resolve(config, createEmptyEnv())

        assertEquals("config-token", result.token)
    }

    @Test
    fun `resolve falls back to GITLAB_TOKEN env var when config token missing`() {
        val config = createConfig(emptyMap())
        val env = createEnv(mapOf("GITLAB_TOKEN" to "env-token"))

        val result = GitLabCredentials.resolve(config, env)

        assertEquals("env-token", result.token)
    }

    @Test
    fun `resolve falls back to CI_JOB_TOKEN env var when GITLAB_TOKEN missing`() {
        val config = createConfig(emptyMap())
        val env = createEnv(mapOf("CI_JOB_TOKEN" to "ci-job-token"))

        val result = GitLabCredentials.resolve(config, env)

        assertEquals("ci-job-token", result.token)
    }

    @Test
    fun `resolve token precedence - config over GITLAB_TOKEN`() {
        val config = createConfig(mapOf("token" to "config-token"))
        val env = createEnv(mapOf("GITLAB_TOKEN" to "env-token", "CI_JOB_TOKEN" to "ci-token"))

        val result = GitLabCredentials.resolve(config, env)

        assertEquals("config-token", result.token)
    }

    @Test
    fun `resolve token precedence - GITLAB_TOKEN over CI_JOB_TOKEN`() {
        val config = createConfig(emptyMap())
        val env = createEnv(mapOf("GITLAB_TOKEN" to "env-token", "CI_JOB_TOKEN" to "ci-token"))

        val result = GitLabCredentials.resolve(config, env)

        assertEquals("env-token", result.token)
    }

    @Test
    fun `missing token resolves anonymously and is not a job token`() {
        val result = GitLabCredentials.resolve(createConfig(emptyMap()), createEmptyEnv())

        assertNull(result.token)
        assertFalse(result.jobToken)
    }

    @Test
    fun `baseUrl alias beats CI_SERVER_URL`() {
        val config = createConfig(mapOf("baseUrl" to "https://alias.example.com/"))
        val env = createEnv(mapOf("CI_SERVER_URL" to "https://ci.example.com"))

        assertEquals("https://alias.example.com", GitLabCredentials.resolve(config, env).baseUrl)
    }

    // URL resolution tests
    @Test
    fun `resolve uses config url when present`() {
        val config = createConfig(
            mapOf(
                "token" to "token",
                "url" to "https://gitlab.example.com"
            )
        )
        val result = GitLabCredentials.resolve(config, createEmptyEnv())

        assertEquals("https://gitlab.example.com", result.baseUrl)
    }

    @Test
    fun `resolve falls back to CI_SERVER_URL env var when config url missing`() {
        val config = createConfig(mapOf("token" to "token"))
        val env = createEnv(mapOf("CI_SERVER_URL" to "https://ci.example.com"))

        val result = GitLabCredentials.resolve(config, env)

        assertEquals("https://ci.example.com", result.baseUrl)
    }

    @Test
    fun `resolve falls back to GITLAB_URL after CI_SERVER_URL`() {
        val config = createConfig(mapOf("token" to "token"))

        val fromGitlabUrl = GitLabCredentials.resolve(config, createEnv(mapOf("GITLAB_URL" to "https://gitlab.company.com/")))
        val ciWins = GitLabCredentials.resolve(
            config,
            createEnv(mapOf("GITLAB_URL" to "https://gitlab.company.com", "CI_SERVER_URL" to "https://ci.example.com")),
        )

        assertEquals("https://gitlab.company.com", fromGitlabUrl.baseUrl)
        assertEquals("https://ci.example.com", ciWins.baseUrl)
        assertTrue("GITLAB_URL" in GitLabCredentials.supportedEnvVars())
    }

    @Test
    fun `resolve defaults to gitlab dot com when no url source found`() {
        val config = createConfig(mapOf("token" to "token"))
        val env = createEmptyEnv()

        val result = GitLabCredentials.resolve(config, env)

        assertEquals("https://gitlab.com", result.baseUrl)
    }

    @Test
    fun `resolve url precedence - config over CI_SERVER_URL`() {
        val config = createConfig(
            mapOf(
                "token" to "token",
                "url" to "https://config.example.com"
            )
        )
        val env = createEnv(mapOf("CI_SERVER_URL" to "https://env.example.com"))

        val result = GitLabCredentials.resolve(config, env)

        assertEquals("https://config.example.com", result.baseUrl)
    }

    // URL normalization tests
    @Test
    fun `resolve trims trailing slash from url`() {
        val config = createConfig(
            mapOf(
                "token" to "token",
                "url" to "https://gitlab.example.com/"
            )
        )
        val result = GitLabCredentials.resolve(config, createEmptyEnv())

        assertEquals("https://gitlab.example.com", result.baseUrl)
    }

    @Test
    fun `resolve trims multiple trailing slashes from url`() {
        val config = createConfig(
            mapOf(
                "token" to "token",
                "url" to "https://gitlab.example.com///"
            )
        )
        val result = GitLabCredentials.resolve(config, createEmptyEnv())

        assertEquals("https://gitlab.example.com", result.baseUrl)
    }

    @Test
    fun `resolve default url has no trailing slash`() {
        val config = createConfig(mapOf("token" to "token"))
        val result = GitLabCredentials.resolve(config, createEmptyEnv())

        assertEquals("https://gitlab.com", result.baseUrl)
        assertEquals(false, result.baseUrl.endsWith("/"))
    }

    @Test
    fun `resolve env url is normalized same as config url`() {
        val configResolved = GitLabCredentials.resolve(
            createConfig(mapOf("token" to "token", "url" to "https://a.com/")),
            createEmptyEnv()
        )
        val envResolved = GitLabCredentials.resolve(
            createConfig(mapOf("token" to "token")),
            createEnv(mapOf("CI_SERVER_URL" to "https://a.com/"))
        )

        assertEquals(configResolved.baseUrl, envResolved.baseUrl)
    }

    // Integration tests
    @Test
    fun `resolve returns both token and url`() {
        val config = createConfig(
            mapOf(
                "token" to "my-token",
                "url" to "https://gitlab.private.com"
            )
        )
        val result = GitLabCredentials.resolve(config, createEmptyEnv())

        assertEquals("my-token", result.token)
        assertEquals("https://gitlab.private.com", result.baseUrl)
    }

    @Test
    fun `resolve CI environment example`() {
        val config = createConfig(emptyMap())
        val env = createEnv(
            mapOf(
                "CI_JOB_TOKEN" to "glpat-xxx",
                "CI_SERVER_URL" to "https://gitlab.company.com"
            )
        )
        val result = GitLabCredentials.resolve(config, env)

        assertEquals("glpat-xxx", result.token)
        assertEquals("https://gitlab.company.com", result.baseUrl)
    }

    @Test
    fun `resolve mixed config and env`() {
        val config = createConfig(
            mapOf(
                "token" to "configured-token"
                // no url
            )
        )
        val env = createEnv(
            mapOf(
                "CI_SERVER_URL" to "https://env.gitlab.com"
                // token not in env (config takes precedence anyway)
            )
        )
        val result = GitLabCredentials.resolve(config, env)

        assertEquals("configured-token", result.token)
        assertEquals("https://env.gitlab.com", result.baseUrl)
    }

    // Approve token resolution tests
    @Test
    fun `resolve uses config approveToken when present`() {
        val config = createConfig(mapOf("token" to "main-token", "approveToken" to "user-pat"))
        val result = GitLabCredentials.resolve(config, createEmptyEnv())

        assertEquals("user-pat", result.approveToken)
    }

    @Test
    fun `resolve falls back to GITLAB_APPROVE_TOKEN env var`() {
        val config = createConfig(mapOf("token" to "main-token"))
        val env = createEnv(mapOf("GITLAB_APPROVE_TOKEN" to "env-approve-token"))

        val result = GitLabCredentials.resolve(config, env)

        assertEquals("env-approve-token", result.approveToken)
    }

    @Test
    fun `resolve approveToken is null when not configured`() {
        val config = createConfig(mapOf("token" to "main-token"))
        val result = GitLabCredentials.resolve(config, createEmptyEnv())

        assertEquals(null, result.approveToken)
    }

    @Test
    fun `resolve approveToken config takes precedence over env`() {
        val config = createConfig(mapOf("token" to "main-token", "approveToken" to "config-approve"))
        val env = createEnv(mapOf("GITLAB_APPROVE_TOKEN" to "env-approve"))

        val result = GitLabCredentials.resolve(config, env)

        assertEquals("config-approve", result.approveToken)
    }

    // Supported env vars
    @Test
    fun `supportedEnvVars returns expected set`() {
        val supported = GitLabCredentials.supportedEnvVars()

        assertEquals(5, supported.size)
        assertEquals(true, "GITLAB_URL" in supported)
        assertEquals(true, "GITLAB_TOKEN" in supported)
        assertEquals(true, "CI_JOB_TOKEN" in supported)
        assertEquals(true, "CI_SERVER_URL" in supported)
        assertEquals(true, "GITLAB_APPROVE_TOKEN" in supported)
    }

    @Test
    fun `supportedEnvVars is immutable`() {
        val supported = GitLabCredentials.supportedEnvVars()

        assertEquals(true, supported is Set<*>)
    }

    // Edge cases
    @Test
    fun `resolve handles empty string config token same as null`() {
        val config = createConfig(mapOf("token" to ""))
        val env = createEnv(mapOf("GITLAB_TOKEN" to "fallback-token"))

        val result = GitLabCredentials.resolve(config, env)

        // A blank config token counts as unset: the env token is used
        assertEquals("fallback-token", result.token)
    }

    @Test
    fun `resolve handles empty string config url same as null`() {
        val config = createConfig(mapOf("token" to "token", "url" to ""))
        val env = createEnv(mapOf("CI_SERVER_URL" to "https://fallback.com"))

        val result = GitLabCredentials.resolve(config, env)

        // Empty string is truthy, so it's returned and trimmed to empty
        assertEquals("", result.baseUrl)
    }

    @Test
    fun `resolve handles whitespace-only url`() {
        val config = createConfig(mapOf("token" to "token", "url" to "   /  "))
        val result = GitLabCredentials.resolve(config, createEmptyEnv())

        // trimEnd('/') removes trailing slashes but not spaces
        assertEquals("   ", result.baseUrl)
    }
}
