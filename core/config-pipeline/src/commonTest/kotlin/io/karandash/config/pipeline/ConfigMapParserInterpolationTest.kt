package io.karandash.config.pipeline

import io.karandash.platform.PlatformEnvironment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Security tests for ConfigMapParser.interpolate() denylist enforcement.
 *
 * CRITICAL: These tests verify that security-sensitive environment variables
 * (PATH, HOME, SSH keys, etc.) cannot be read through config file ${...} interpolation.
 *
 * The denylist approach allows any env var EXCEPT those in EnvDeclaration.DENYLIST.
 */
class ConfigMapParserInterpolationTest {
    @Test
    fun `interpolate resolves non-denylisted env var with default`() {
        val value = "token is \${GITLAB_TOKEN}"
        val defaults = mapOf("GITLAB_TOKEN" to "fallback-token")
        val result = ConfigMapParser.interpolate(value, defaults = defaults)

        val actualToken = PlatformEnvironment.getEnv("GITLAB_TOKEN")
        val expected = "token is ${actualToken ?: "fallback-token"}"
        assertEquals(expected, result)
    }

    @Test
    fun `interpolate resolves GITHUB_TOKEN with default`() {
        val value = "\${GITHUB_TOKEN}/api"
        val defaults = mapOf("GITHUB_TOKEN" to "gh-fallback")
        val result = ConfigMapParser.interpolate(value, defaults = defaults)

        val actualToken = PlatformEnvironment.getEnv("GITHUB_TOKEN")
        val expected = "${actualToken ?: "gh-fallback"}/api"
        assertEquals(expected, result)
    }

    @Test
    fun `interpolate resolves AWS_REGION with default`() {
        val value = "region=\${AWS_REGION}"
        val defaults = mapOf("AWS_REGION" to "us-east-1")
        val result = ConfigMapParser.interpolate(value, defaults = defaults)

        val actualRegion = PlatformEnvironment.getEnv("AWS_REGION")
        val expected = "region=${actualRegion ?: "us-east-1"}"
        assertEquals(expected, result)
    }

    @Test
    fun `SECURITY - interpolate blocks HOME env var`() {
        val value = "secret=\${HOME}"
        val result = ConfigMapParser.interpolate(value, emptyMap())

        // HOME is in the denylist, so it should remain unchanged
        assertEquals("secret=\${HOME}", result)
    }

    @Test
    fun `SECURITY - interpolate blocks PATH env var`() {
        val value = "\${PATH}/bin"
        val result = ConfigMapParser.interpolate(value, emptyMap())

        assertEquals("\${PATH}/bin", result)
    }

    @Test
    fun `SECURITY - interpolate blocks USER env var`() {
        val value = "user=\${USER}"
        val result = ConfigMapParser.interpolate(value, emptyMap())

        assertEquals("user=\${USER}", result)
    }

    @Test
    fun `SECURITY - interpolate blocks SSH_AUTH_SOCK env var`() {
        val value = "\${SSH_AUTH_SOCK}"
        val result = ConfigMapParser.interpolate(value, emptyMap())

        assertEquals("\${SSH_AUTH_SOCK}", result)
    }

    @Test
    fun `SECURITY - interpolate blocks AWS_SECRET_ACCESS_KEY env var`() {
        val value = "\${AWS_SECRET_ACCESS_KEY}"
        val result = ConfigMapParser.interpolate(value, emptyMap())

        assertEquals("\${AWS_SECRET_ACCESS_KEY}", result)
    }

    @Test
    fun `interpolate handles multiple env vars with defaults`() {
        val value = "\${GITLAB_TOKEN}:\${GITHUB_TOKEN}"
        val defaults = mapOf("GITLAB_TOKEN" to "gl-default", "GITHUB_TOKEN" to "gh-default")
        val result = ConfigMapParser.interpolate(value, defaults = defaults)

        val gitlabToken = PlatformEnvironment.getEnv("GITLAB_TOKEN") ?: "gl-default"
        val githubToken = PlatformEnvironment.getEnv("GITHUB_TOKEN") ?: "gh-default"

        assertEquals("$gitlabToken:$githubToken", result)
    }

    @Test
    fun `SECURITY - interpolate blocks denylisted var but resolves non-denylisted in same string`() {
        val value = "\${GITLAB_TOKEN}:\${HOME}"
        val defaults = mapOf("GITLAB_TOKEN" to "gl-default")
        val result = ConfigMapParser.interpolate(value, defaults = defaults)

        val gitlabToken = PlatformEnvironment.getEnv("GITLAB_TOKEN") ?: "gl-default"

        // GITLAB_TOKEN resolved, HOME blocked (left as placeholder)
        assertEquals("$gitlabToken:\${HOME}", result)
    }

    @Test
    fun `interpolate with custom denylist blocks specified vars`() {
        val customDenylist = setOf("MY_SECRET_VAR")
        val value = "\${MY_SECRET_VAR}"

        val result = ConfigMapParser.interpolate(value, emptyMap(), denylist = customDenylist)

        assertEquals("\${MY_SECRET_VAR}", result)
    }

    @Test
    fun `interpolate handles text without placeholders`() {
        val value = "no placeholders here"
        val result = ConfigMapParser.interpolate(value, emptyMap())
        assertEquals("no placeholders here", result)
    }

    @Test
    fun `interpolate handles empty string`() {
        val value = ""
        val result = ConfigMapParser.interpolate(value, emptyMap())
        assertEquals("", result)
    }

    @Test
    fun `interpolate handles escaped dollar sign pattern`() {
        val value = "price: $100"
        val result = ConfigMapParser.interpolate(value, emptyMap())
        assertEquals("price: $100", result)
    }

    @Test
    fun `interpolate collects missing vars when missingVars set provided`() {
        val missingVars = mutableSetOf<String>()
        val value = "\${KARANDASH_TEST_NONEXISTENT_VAR_XYZ123}"

        val result = ConfigMapParser.interpolate(value, emptyMap(), missingVars = missingVars)

        assertEquals("", result)
        assertTrue(missingVars.contains("KARANDASH_TEST_NONEXISTENT_VAR_XYZ123"))
    }

    @Test
    fun `interpolate uses default when env var is unset`() {
        val defaults = mapOf("KARANDASH_TEST_NONEXISTENT_VAR_XYZ123" to "my-default")
        val value = "\${KARANDASH_TEST_NONEXISTENT_VAR_XYZ123}"

        val result = ConfigMapParser.interpolate(value, defaults = defaults)
        assertEquals("my-default", result)
    }

    @Test
    fun `interpolate resolves KARANDASH vars`() {
        val value = "\${KARANDASH_CONFIG_URL}"
        val defaults = mapOf("KARANDASH_CONFIG_URL" to "https://default.config")
        val result = ConfigMapParser.interpolate(value, defaults = defaults)

        val actual = PlatformEnvironment.getEnv("KARANDASH_CONFIG_URL") ?: "https://default.config"
        assertEquals(actual, result)
    }
}
