@file:OptIn(ExperimentalForeignApi::class)

package io.karandash.helpers

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A K/N guest sees only the env the host put in its context, minus denylisted and secret-looking names. */
class CAbiBridgeEnvTest {
    @Test
    fun envComesOnlyFromHostContextAndDropsSecrets() {
        val ctxJson = """{"env":{"CI":"true","HOME":"/home/x","KARANDASH_GITLAB_TOKEN":"host-secret"}}"""
        val ctx = memScoped { CAbiBridge.contextFromJson(ctxJson.cstr.ptr) }
        assertEquals("true", ctx.env("CI"))
        assertNull(ctx.env("HOME"), "denylisted name must not reach the guest")
        assertNull(ctx.env("KARANDASH_GITLAB_TOKEN"), "secret-looking name must not reach the guest")
        assertNull(ctx.env("PATH"), "the guest process env must not be read")
    }

    @Test
    fun contextKeepsEveryHostField() {
        val ctxJson =
            """{"pluginConfig":{"a":1},"hostVersion":"9.9.9","workingDirectory":"/w","configDir":"/c",""" +
                """"allowLocalhost":true,"patterns":{"@ticket":{"source":"abc","options":["IGNORE_CASE"]}}}"""
        val ctx = memScoped { CAbiBridge.contextFromJson(ctxJson.cstr.ptr) }
        assertEquals("9.9.9", ctx.hostVersion)
        assertEquals("/w", ctx.workingDirectory)
        assertEquals("/c", ctx.configDir)
        assertTrue(ctx.allowLocalhost)
        assertEquals("""{"a":1}""", ctx.pluginConfig.toString())
        assertTrue(ctx.resolvePattern("@ticket").matches("ABC"))
    }

    @Test
    fun missingContextHasNoEnv() {
        assertNull(CAbiBridge.contextFromJson(null).env("CI"))
    }
}
