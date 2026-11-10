package io.karandash.config.pipeline

import io.karandash.api.errors.ConfigException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class EnvDeclarationTest {
    @Test
    fun `EMPTY has no vars`() {
        assertEquals(0, EnvDeclaration.EMPTY.names.size)
        assertEquals(0, EnvDeclaration.EMPTY.defaults.size)
    }

    @Test
    fun `names returns all declared var names`() {
        val decl = EnvDeclaration(mapOf("A" to null, "B" to "default"))
        assertEquals(setOf("A", "B"), decl.names)
    }

    @Test
    fun `defaults returns only non-null entries`() {
        val decl = EnvDeclaration(mapOf("A" to null, "B" to "val-b", "C" to "val-c"))
        assertEquals(mapOf("B" to "val-b", "C" to "val-c"), decl.defaults)
    }

    @Test
    fun `union merges all names from multiple declarations`() {
        val a = EnvDeclaration(mapOf("X" to null, "Y" to "a-default"))
        val b = EnvDeclaration(mapOf("Y" to "b-default", "Z" to null))
        val result = EnvDeclaration.union(listOf(a, b))

        assertEquals(setOf("X", "Y", "Z"), result.names)
    }

    @Test
    fun `union keeps first non-null default`() {
        val a = EnvDeclaration(mapOf("X" to "first-default"))
        val b = EnvDeclaration(mapOf("X" to "second-default"))
        val result = EnvDeclaration.union(listOf(a, b))

        assertEquals("first-default", result.vars["X"])
    }

    @Test
    fun `union fills null default from later declaration`() {
        val a = EnvDeclaration(mapOf("X" to null))
        val b = EnvDeclaration(mapOf("X" to "later-default"))
        val result = EnvDeclaration.union(listOf(a, b))

        assertEquals("later-default", result.vars["X"])
    }

    @Test
    fun `union of empty list returns EMPTY`() {
        assertEquals(EnvDeclaration.EMPTY, EnvDeclaration.union(emptyList()))
    }

    @Test
    fun `validate passes for allowed var names`() {
        val decl = EnvDeclaration(mapOf("TASK_ID" to null, "JIRA_KEY" to "PROJ"))
        validateEnvDeclaration(decl) // Should not throw
    }

    @Test
    fun `SECURITY - validate rejects PATH`() {
        val decl = EnvDeclaration(mapOf("PATH" to null))
        val ex = assertFailsWith<ConfigException> {
            validateEnvDeclaration(decl)
        }
        assertTrue(ex.message!!.contains("E0021"))
        assertTrue(ex.message!!.contains("PATH"))
    }

    @Test
    fun `SECURITY - validate rejects HOME`() {
        val decl = EnvDeclaration(mapOf("HOME" to null))
        val ex = assertFailsWith<ConfigException> {
            validateEnvDeclaration(decl)
        }
        assertTrue(ex.message!!.contains("E0021"))
        assertTrue(ex.message!!.contains("HOME"))
    }

    @Test
    fun `SECURITY - validate rejects SSH_AUTH_SOCK`() {
        val decl = EnvDeclaration(mapOf("SSH_AUTH_SOCK" to null))
        val ex = assertFailsWith<ConfigException> {
            validateEnvDeclaration(decl)
        }
        assertTrue(ex.message!!.contains("E0021"))
    }

    @Test
    fun `SECURITY - validate rejects AWS_SECRET_ACCESS_KEY`() {
        val decl = EnvDeclaration(mapOf("AWS_SECRET_ACCESS_KEY" to null))
        val ex = assertFailsWith<ConfigException> {
            validateEnvDeclaration(decl)
        }
        assertTrue(ex.message!!.contains("E0021"))
    }

    @Test
    fun `SECURITY - validate reports all restricted vars in message`() {
        val decl = EnvDeclaration(mapOf("PATH" to null, "HOME" to null, "TASK_ID" to null))
        val ex = assertFailsWith<ConfigException> {
            validateEnvDeclaration(decl)
        }
        assertTrue(ex.message!!.contains("PATH"))
        assertTrue(ex.message!!.contains("HOME"))
    }

    @Test
    fun `validate passes for EMPTY`() {
        validateEnvDeclaration(EnvDeclaration.EMPTY) // Should not throw
    }

    @Test
    fun `the config error comes from the one generic denylist check`() {
        val decl = EnvDeclaration(mapOf("PATH" to null))
        val generic = assertFailsWith<IllegalArgumentException> { EnvDeclaration.validate(decl) }
        val ex = assertFailsWith<ConfigException> { validateEnvDeclaration(decl) }

        assertIs<IllegalArgumentException>(ex.cause)
        assertEquals("[E0021] ${generic.message}", ex.message)
    }
}
