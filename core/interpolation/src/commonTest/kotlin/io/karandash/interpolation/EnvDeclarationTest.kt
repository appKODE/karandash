package io.karandash.interpolation

import io.karandash.platform.PlatformEnvironment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EnvDeclarationTest {
    @Test
    fun `names include every var and defaults only the non null ones`() {
        val declaration = EnvDeclaration(mapOf("A" to null, "B" to "b", "C" to ""))

        assertEquals(setOf("A", "B", "C"), declaration.names)
        assertEquals(mapOf("B" to "b", "C" to ""), declaration.defaults)
    }

    @Test
    fun `union of nothing is empty`() {
        assertEquals(EnvDeclaration.EMPTY, EnvDeclaration.union(emptyList()))
    }

    @Test
    fun `union keeps every name and the first non null default`() {
        val union = EnvDeclaration.union(
            listOf(
                EnvDeclaration(mapOf("A" to null, "B" to "base")),
                EnvDeclaration(mapOf("A" to "child", "B" to "child", "C" to null)),
                EnvDeclaration(mapOf("A" to "grandchild", "C" to "c")),
            ),
        )

        assertEquals(mapOf("A" to "child", "B" to "base", "C" to "c"), union.vars)
    }

    @Test
    fun `validate accepts unrestricted names`() {
        EnvDeclaration.validate(EnvDeclaration(mapOf("TASK_ID" to null, "JIRA" to "x")))
    }

    @Test
    fun `validate rejects every denylisted name`() {
        KarandashEnvSecurity.DENYLIST.forEach { name ->
            val error = assertFailsWith<IllegalArgumentException> {
                EnvDeclaration.validate(EnvDeclaration(mapOf("OK" to null, name to null)))
            }
            assertTrue(error.message.orEmpty().contains(name))
            assertTrue(!error.message.orEmpty().contains("OK,"))
        }
    }

    @Test
    fun `validate honours a custom denylist`() {
        assertFailsWith<IllegalArgumentException> {
            EnvDeclaration.validate(EnvDeclaration(mapOf("SECRET" to null)), denylist = setOf("SECRET"))
        }
        EnvDeclaration.validate(EnvDeclaration(mapOf("PATH" to null)), denylist = emptySet())
    }

    @Test
    fun `onResolve reports the source of each value`() {
        val events = mutableListOf<Triple<String, String, String?>>()
        val path = checkNotNull(PlatformEnvironment.getEnv("PATH")) { "PATH must be set for this test" }

        val result = EnvInterpolation.interpolate(
            "\${PATH}|\${K_DEF}|\${K_INLINE:-i}|\${K_MISSING}|\${HOME}",
            denylist = setOf("HOME"),
            defaults = mapOf("K_DEF" to "d"),
        ) { name, source, value -> events += Triple(name, source, value) }

        assertEquals("$path|d|i||\${HOME}", result)
        assertEquals(
            listOf(
                Triple("PATH", "env", path),
                Triple("K_DEF", "default", "d"),
                Triple("K_INLINE", "inline", "i"),
                Triple("K_MISSING", "MISSING", null),
                Triple("HOME", "denied", null),
            ),
            events,
        )
    }

    @Test
    fun `text without a closed token is returned unchanged`() {
        listOf("plain", "\$VAR", "\${UNCLOSED", "\${}", "price \$5").forEach { text ->
            assertEquals(text, EnvInterpolation.interpolate(text), text)
        }
    }

    @Test
    fun `unbraced references are reported only for declared vars`() {
        val declared = setOf("TASK_ID", "JIRA_KEY")

        assertEquals(
            listOf("TASK_ID", "JIRA_KEY"),
            EnvInterpolation.detectUnbracedReferences("id=\$TASK_ID key=\$JIRA_KEY other=\$OTHER_VAR", declared),
        )
        assertEquals(emptyList(), EnvInterpolation.detectUnbracedReferences("\${TASK_ID} ok", declared))
        assertEquals(emptyList(), EnvInterpolation.detectUnbracedReferences("no dollar", declared))
        assertEquals(emptyList(), EnvInterpolation.detectUnbracedReferences("\$TASK_ID", emptySet()))
        assertEquals(emptyList(), EnvInterpolation.detectUnbracedReferences("\$AB", setOf("AB")))
    }
}
