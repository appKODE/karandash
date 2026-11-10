package io.karandash.interpolation

import io.karandash.platform.PlatformEnvironment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** credential-looking env names are hidden from policies and JS, not from `${}` interpolation. */
class KarandashEnvSecurityTest {
    @Test
    fun `isSensitive matches token api key secret and password names`() {
        listOf("GITLAB_TOKEN", "ci_job_token", "OPENAI_API_KEY", "AWS_SECRET_X", "SMTP_PASSWORD").forEach {
            assertTrue(KarandashEnvSecurity.isSensitive(it), it)
        }
        listOf("CI_PROJECT_ID", "API_KEYRING_DIR", "KARANDASH_LOG_LEVEL").forEach {
            assertFalse(KarandashEnvSecurity.isSensitive(it), it)
        }
    }

    @Test
    fun `policy lookup hides sensitive and denylisted names`() {
        val lookup = KarandashEnvSecurity.withoutSecrets { "v-$it" }
        assertNull(lookup("GITLAB_TOKEN"))
        assertNull(lookup("HOME"))
        assertEquals("v-CI_COMMIT_BRANCH", lookup("CI_COMMIT_BRANCH"))
    }

    @Test
    fun `config interpolation still resolves token and api key vars`() {
        val vars = listOf("KARANDASH_GITLAB_TOKEN", "KARANDASH_PROJECT_TOKEN", "BIFROST_API_KEY", "KARANDASH_AI_MAX_TOKENS")
        val denied = mutableSetOf<String>()
        vars.forEach { name ->
            // the env value wins over the default when set (native test runs blank provider tokens)
            val result = EnvInterpolation.interpolate("\${$name}", KarandashEnvSecurity.DENYLIST, mapOf(name to "v"), deniedVars = denied)
            assertTrue(result == "v" || result == PlatformEnvironment.getEnv(name), "$name -> $result")
        }
        assertEquals(emptySet(), denied)
    }
}
