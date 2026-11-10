package io.karandash.plugins.ai.core.config

import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TeamNamespaceResolverTest {
    private fun change(path: String) = FileChange(path = path, changeType = ChangeType.MODIFIED)

    @Test
    fun `empty teams returns empty ownership`() {
        val result = TeamNamespaceResolver.resolveOwnership(listOf(change("src/main.kt")), emptyList())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `empty changes returns empty ownership`() {
        val teams = listOf(TeamConfig(name = "backend", owns = listOf("src/**")))
        val result = TeamNamespaceResolver.resolveOwnership(emptyList(), teams)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `file matched to owning team`() {
        val teams = listOf(TeamConfig(name = "backend", owns = listOf("src/**")))
        val changes = listOf(change("src/main.kt"))
        val result = TeamNamespaceResolver.resolveOwnership(changes, teams)
        assertEquals(listOf(changes[0]), result["backend"])
    }

    @Test
    fun `multiple teams can own the same file`() {
        val teams = listOf(
            TeamConfig(name = "backend", owns = listOf("src/**")),
            TeamConfig(name = "platform", owns = listOf("**/*.kt")),
        )
        val changes = listOf(change("src/main.kt"))
        val result = TeamNamespaceResolver.resolveOwnership(changes, teams)
        assertEquals(listOf(changes[0]), result["backend"])
        assertEquals(listOf(changes[0]), result["platform"])
    }

    @Test
    fun `unowned file not included in any team`() {
        val teams = listOf(TeamConfig(name = "frontend", owns = listOf("ui/**")))
        val changes = listOf(change("src/main.kt"))
        val result = TeamNamespaceResolver.resolveOwnership(changes, teams)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `affectedTeams returns correct set`() {
        val teams = listOf(
            TeamConfig(name = "backend", owns = listOf("src/**")),
            TeamConfig(name = "frontend", owns = listOf("ui/**")),
        )
        val changes = listOf(change("src/main.kt"), change("ui/App.tsx"))
        val affected = TeamNamespaceResolver.affectedTeams(changes, teams)
        assertEquals(setOf("backend", "frontend"), affected)
    }

    @Test
    fun `affectedTeams excludes teams with no matching files`() {
        val teams = listOf(
            TeamConfig(name = "backend", owns = listOf("src/**")),
            TeamConfig(name = "docs", owns = listOf("docs/**")),
        )
        val changes = listOf(change("src/main.kt"))
        val affected = TeamNamespaceResolver.affectedTeams(changes, teams)
        assertEquals(setOf("backend"), affected)
    }

    @Test
    fun `double-star glob matches nested paths`() {
        val teams = listOf(TeamConfig(name = "backend", owns = listOf("src/**/*.kt")))
        val changes = listOf(change("src/com/example/deep/Foo.kt"))
        val result = TeamNamespaceResolver.resolveOwnership(changes, teams)
        assertEquals(listOf(changes[0]), result["backend"])
    }

    @Test
    fun `single-star glob does not cross directory boundary`() {
        val teams = listOf(TeamConfig(name = "backend", owns = listOf("src/*.kt")))
        val changes = listOf(change("src/sub/Foo.kt"))
        val result = TeamNamespaceResolver.resolveOwnership(changes, teams)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `owns is a GlobMatcher path glob anchored at the repo root`() {
        val teams = listOf(
            TeamConfig(name = "security", owns = listOf("**/auth/**")),
            TeamConfig(name = "backend", owns = listOf("src/*.kt")),
        )
        val changes = listOf(change("auth/Login.kt"), change("lib/src/Foo.kt"))
        val result = TeamNamespaceResolver.resolveOwnership(changes, teams)

        assertEquals(mapOf("security" to listOf(changes[0])), result)
    }

    @Test
    fun `invalid glob pattern does not throw`() {
        val teams = listOf(TeamConfig(name = "bad", owns = listOf("[invalid")))
        val changes = listOf(change("src/main.kt"))
        val result = TeamNamespaceResolver.resolveOwnership(changes, teams)
        assertTrue(result.isEmpty())
    }
}
