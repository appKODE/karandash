package io.karandash.interpolation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [EnvInterpolation] covering inline default syntax (`${VAR:-default}`)
 * and denied variable tracking.
 */
class EnvInterpolationTest {
    // ── ${VAR:-default} inline default syntax ──────────────────

    @Test
    fun `inline default used when var unset`() {
        val result = EnvInterpolation.interpolate(
            value = "\${KARANDASH_TEST_NONEXISTENT_XYZ:-fallback}",
            denylist = emptySet(),
        )
        assertEquals("fallback", result)
    }

    @Test
    fun `inline default ignored when var set via defaults map`() {
        val result = EnvInterpolation.interpolate(
            value = "\${MY_VAR:-inline-default}",
            defaults = mapOf("MY_VAR" to "actual"),
        )
        assertEquals("actual", result)
    }

    @Test
    fun `inline default empty string`() {
        val result = EnvInterpolation.interpolate(
            value = "\${KARANDASH_TEST_NONEXISTENT_XYZ:-}",
            denylist = emptySet(),
        )
        assertEquals("", result)
    }

    @Test
    fun `inline default with colons`() {
        val result = EnvInterpolation.interpolate(
            value = "\${KARANDASH_TEST_NONEXISTENT_XYZ:-http://example.com}",
            denylist = emptySet(),
        )
        assertEquals("http://example.com", result)
    }

    @Test
    fun `inline default with spaces`() {
        val result = EnvInterpolation.interpolate(
            value = "\${KARANDASH_TEST_NONEXISTENT_XYZ:-hello world}",
            denylist = emptySet(),
        )
        assertEquals("hello world", result)
    }

    @Test
    fun `denied var with inline default still stays as placeholder`() {
        val result = EnvInterpolation.interpolate(
            value = "\${HOME:-/tmp}",
            denylist = setOf("HOME"),
        )
        assertEquals("\${HOME:-/tmp}", result)
    }

    @Test
    fun `env block default takes priority over inline default`() {
        val result = EnvInterpolation.interpolate(
            value = "\${MY_VAR:-inline-default}",
            defaults = mapOf("MY_VAR" to "env-default"),
        )
        assertEquals("env-default", result)
    }

    @Test
    fun `multiple vars with inline defaults in same string`() {
        val result = EnvInterpolation.interpolate(
            value = "\${KARANDASH_TEST_NONEXISTENT_A:-x}/\${KARANDASH_TEST_NONEXISTENT_B:-y}",
            denylist = emptySet(),
        )
        assertEquals("x/y", result)
    }

    @Test
    fun `inline default does not add to missingVars`() {
        val missingVars = mutableSetOf<String>()
        val result = EnvInterpolation.interpolate(
            value = "\${KARANDASH_TEST_NONEXISTENT_XYZ:-fallback}",
            missingVars = missingVars,
        )
        assertEquals("fallback", result)
        assertTrue(missingVars.isEmpty(), "Var with inline default should not be reported as missing")
    }

    @Test
    fun `plain var without inline default still works`() {
        val result = EnvInterpolation.interpolate(
            value = "\${MY_VAR}",
            defaults = mapOf("MY_VAR" to "value"),
        )
        assertEquals("value", result)
    }

    @Test
    fun `plain var without default is added to missingVars`() {
        val missingVars = mutableSetOf<String>()
        val result = EnvInterpolation.interpolate(
            value = "\${KARANDASH_TEST_NONEXISTENT_XYZ}",
            missingVars = missingVars,
        )
        assertEquals("", result)
        assertTrue(missingVars.contains("KARANDASH_TEST_NONEXISTENT_XYZ"))
    }

    // ── Denied var tracking ────────────────────────────────────

    @Test
    fun `denied vars tracked in deniedVars set`() {
        val deniedVars = mutableSetOf<String>()
        val result = EnvInterpolation.interpolate(
            value = "\${HOME}/config",
            denylist = setOf("HOME"),
            deniedVars = deniedVars,
        )
        assertEquals("\${HOME}/config", result)
        assertTrue(deniedVars.contains("HOME"))
    }

    @Test
    fun `non-denied vars not tracked in deniedVars`() {
        val deniedVars = mutableSetOf<String>()
        EnvInterpolation.interpolate(
            value = "\${MY_VAR}",
            denylist = setOf("HOME"),
            defaults = mapOf("MY_VAR" to "value"),
            deniedVars = deniedVars,
        )
        assertFalse(deniedVars.contains("MY_VAR"))
        assertTrue(deniedVars.isEmpty())
    }

    @Test
    fun `multiple denied vars all tracked`() {
        val deniedVars = mutableSetOf<String>()
        EnvInterpolation.interpolate(
            value = "\${HOME}/\${PATH}/\${USER}",
            denylist = setOf("HOME", "PATH", "USER"),
            deniedVars = deniedVars,
        )
        assertEquals(setOf("HOME", "PATH", "USER"), deniedVars)
    }

    @Test
    fun `denied var with inline default tracked in deniedVars`() {
        val deniedVars = mutableSetOf<String>()
        val result = EnvInterpolation.interpolate(
            value = "\${HOME:-/tmp}",
            denylist = setOf("HOME"),
            deniedVars = deniedVars,
        )
        assertEquals("\${HOME:-/tmp}", result)
        assertTrue(deniedVars.contains("HOME"))
    }

    @Test
    fun `mixed denied and non-denied vars tracked correctly`() {
        val deniedVars = mutableSetOf<String>()
        val missingVars = mutableSetOf<String>()
        val result = EnvInterpolation.interpolate(
            value = "\${HOME}:\${MY_VAR}",
            denylist = setOf("HOME"),
            defaults = mapOf("MY_VAR" to "val"),
            missingVars = missingVars,
            deniedVars = deniedVars,
        )
        assertEquals("\${HOME}:val", result)
        assertTrue(deniedVars.contains("HOME"))
        assertFalse(deniedVars.contains("MY_VAR"))
        assertTrue(missingVars.isEmpty())
    }
}
