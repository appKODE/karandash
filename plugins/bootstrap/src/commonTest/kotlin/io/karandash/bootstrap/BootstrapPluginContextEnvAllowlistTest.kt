package io.karandash.bootstrap

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BootstrapPluginContextEnvAllowlistTest {
    @Test
    fun `module context tags log lines with the module id`() {
        val out = mutableListOf<String>()
        val base = BootstrapPluginContext(
            moduleId = null,
            hostVersion = "1.0",
            workingDirectory = ".",
            baseLogger = CliLogger(verbose = true, sink = out::add),
        )
        base.forModule("jira").logger.debug("enrich: ok")
        base.logger.debug("bare")

        assertTrue(out[0].endsWith("[DEBUG] [module=jira] enrich: ok"), out[0])
        assertTrue("module=" !in out[1], out[1])
    }

    @Test
    fun `context blocks denylisted vars`() {
        val ctx = BootstrapPluginContext(
            moduleId = null,
            hostVersion = "1.0",
            workingDirectory = "."
        )
        assertFalse(ctx.isEnvAllowed("PATH"))
        assertFalse(ctx.isEnvAllowed("HOME"))
        assertFalse(ctx.isEnvAllowed("SSH_AUTH_SOCK"))
        assertFalse(ctx.isEnvAllowed("AWS_SECRET_ACCESS_KEY"))
    }

    @Test
    fun `context permits non-denylisted vars`() {
        val ctx = BootstrapPluginContext(
            moduleId = null,
            hostVersion = "1.0",
            workingDirectory = "."
        )
        assertTrue(ctx.isEnvAllowed("KARANDASH_CONFIG_URL"))
        assertTrue(ctx.isEnvAllowed("KARANDASH_CONFIG_TOKEN"))
        assertTrue(ctx.isEnvAllowed("KARANDASH_PLUGIN_DIR"))
        assertTrue(ctx.isEnvAllowed("GITLAB_TOKEN"))
        assertTrue(ctx.isEnvAllowed("GITHUB_TOKEN"))
        assertTrue(ctx.isEnvAllowed("ANTHROPIC_API_KEY"))
        assertTrue(ctx.isEnvAllowed("OPENAI_API_KEY"))
        assertTrue(ctx.isEnvAllowed("CUSTOM_SECRET"))
    }

    @Test
    fun `forModule preserves denylist semantics`() {
        val ctx = BootstrapPluginContext(
            moduleId = null,
            hostVersion = "1.0",
            workingDirectory = "."
        )
        val scoped = ctx.forModule("test.module") as BootstrapPluginContext

        assertTrue(scoped.isEnvAllowed("GITLAB_TOKEN"))
        assertTrue(scoped.isEnvAllowed("OPENAI_API_KEY"))
        assertFalse(scoped.isEnvAllowed("PATH"))
        assertFalse(scoped.isEnvAllowed("HOME"))
    }

    @Test
    fun `env returns null for denylisted vars`() {
        val envReader: (String) -> String? = { name ->
            when (name) {
                "PATH" -> "/usr/bin"
                "GITLAB_TOKEN" -> "test-token"
                else -> null
            }
        }
        val ctx = BootstrapPluginContext(
            moduleId = null,
            hostVersion = "1.0",
            workingDirectory = ".",
            envReader = envReader,
        )

        // Denylisted var returns null even if set
        assertNull(ctx.env("PATH"))
        // Non-denylisted var returns actual value
        assertEquals("test-token", ctx.env("GITLAB_TOKEN"))
    }
}
