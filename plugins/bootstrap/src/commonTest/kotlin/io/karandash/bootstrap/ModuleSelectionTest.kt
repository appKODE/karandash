package io.karandash.bootstrap

import io.karandash.api.Module
import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ModuleSelectionTest {
    private class Named(
        override val id: String,
        override val name: String = id,
        val key: String = id,
    ) : Module {
        override val version = "1"
        override val type = ModuleType.REPORTER

        override suspend fun initialize(context: PluginContext) = true
    }

    private val gitlab = Named("io.karandash.gitlab", "GitLab")
    private val github = Named("io.karandash.github", "GitHub")
    private val gitea = Named("io.karandash.gitea", "Gitea")
    private val all = listOf(gitlab, github, gitea)

    private fun hint(
        hint: String?,
        preferred: Set<String> = emptySet(),
        modules: List<Named> = all,
        fuzzy: Boolean = true,
    ) = ModuleSelector.selectByHint(modules, hint, preferred, "platforms", fuzzy)

    @Test
    fun `hint selects an exact id before fuzzy matches`() {
        assertEquals(gitlab, hint("io.karandash.gitlab").module)
        assertEquals(gitlab, hint("IO_KARANDASH_GITLAB").module)
        assertEquals(gitea, hint("gitea").module)
        assertEquals(github, hint("GitHub").module)
    }

    @Test
    fun `ambiguous hints name every candidate`() {
        val fuzzy = hint("git")
        assertNull(fuzzy.module)
        assertEquals(
            "Multiple platforms could match 'git': io.karandash.gitlab, io.karandash.github, io.karandash.gitea. " +
                "Use config.plugins.enabled to select one.",
            fuzzy.error,
        )
        val twins = listOf(Named("a.x"), Named("a-x"))
        assertEquals("Multiple platforms match 'a.x': a.x, a-x", hint("a.x", modules = twins).error)
    }

    @Test
    fun `hint without a match falls back to a single preferred module`() {
        assertEquals(github, hint("bitbucket", preferred = setOf(github.id)).module)
        assertEquals(
            "No platforms match 'bitbucket' among preferred modules: io.karandash.gitlab, io.karandash.github. " +
                "Narrow config.plugins.enabled to one module for this role.",
            hint("bitbucket", preferred = setOf(gitlab.id, github.id)).error,
        )
        assertEquals(ModuleSelector.SelectionResult(), hint("bitbucket"))
        assertEquals(ModuleSelector.SelectionResult(), hint("gitl", fuzzy = false))
    }

    @Test
    fun `preferred modules narrow the hint candidates`() {
        assertEquals(gitea, hint("git", preferred = setOf(gitea.id)).module)
        assertEquals(gitlab, hint("io.karandash.gitlab", preferred = setOf("unknown")).module)
    }

    @Test
    fun `no hint picks the only candidate or the only preferred one`() {
        assertEquals(ModuleSelector.SelectionResult(), hint(null, modules = emptyList()))
        assertEquals(gitea, hint(null, modules = listOf(gitea)).module)
        assertEquals(gitea, hint("", preferred = setOf(gitea.id)).module)
        assertEquals(
            "Multiple platforms are available: io.karandash.gitlab, io.karandash.github, io.karandash.gitea. " +
                "Use config.plugins.enabled to select one.",
            hint(null).error,
        )
        assertEquals(
            "Multiple platforms are available: io.karandash.gitlab, io.karandash.github. Use config.plugins.enabled to select one.",
            hint(null, preferred = setOf(gitlab.id, github.id)).error,
        )
    }

    private fun exact(
        key: String,
        modules: List<Named>,
        preferred: Set<String> = emptySet(),
    ) = ModuleSelector.selectByExactKey(modules, key, preferred, "reporters", "format", Named::key)

    @Test
    fun `exact key selection prefers preferred modules and reports duplicates`() {
        val a = Named("a", key = "json")
        val b = Named("b", key = "JSON")
        val c = Named("c", key = "md")

        assertEquals(ModuleSelector.SelectionResult(), exact("", listOf(a)))
        assertEquals(ModuleSelector.SelectionResult(), exact("xml", listOf(a, c)))
        assertEquals(c, exact("md", listOf(a, b, c)).module)
        assertEquals(b, exact("json", listOf(a, b, c), setOf("b")).module)
        assertEquals("Multiple preferred reporters match format 'json': a, b", exact("json", listOf(a, b), setOf("a", "b")).error)
        assertEquals(
            "Multiple reporters match format 'json': a, b. Use config.plugins.enabled to select one.",
            exact("json", listOf(a, b), setOf("c")).error,
        )
    }

    @Test
    fun `normalizeKey matches across separators and case`() {
        val keys = listOf("io.karandash.git-lab", "IO_KARANDASH_GIT_LAB", "io.karandash.gitLab")
        assertEquals(1, keys.map(ModuleSelector::normalizeKey).toSet().size, keys.map(ModuleSelector::normalizeKey).toString())
    }
}
