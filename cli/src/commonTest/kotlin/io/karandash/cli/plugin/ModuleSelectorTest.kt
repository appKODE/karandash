package io.karandash.cli.plugin

import io.karandash.api.Module
import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import io.karandash.bootstrap.ModuleSelector
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModuleSelectorTest {
    @Serializable
    private data class TestModule(
        override val id: String,
        override val name: String,
        override val version: String = "1.0.0",
        override val type: ModuleType = ModuleType.REPORTER,
        val key: String = ""
    ) : Module {
        override suspend fun initialize(context: PluginContext): Boolean = true
    }

    @Test
    fun `normalizeKey removes separators and lowercases`() {
        assertEquals("azuredevops", ModuleSelector.normalizeKey("Azure-DevOps"))
        assertEquals("github", ModuleSelector.normalizeKey("Git Hub"))
        assertEquals("gitea", ModuleSelector.normalizeKey("gitea_"))
    }

    @Test
    fun `selectByHint resolves exact ID match`() {
        val modules = listOf(
            TestModule(id = "github-provider", name = "GitHub Provider"),
            TestModule(id = "gitlab-provider", name = "GitLab Provider")
        )

        val result = ModuleSelector.selectByHint(
            modules = modules,
            hint = "GitHub-Provider",
            preferredIds = emptySet(),
            typeName = "dynamic providers"
        )

        assertEquals("github-provider", result.module?.id)
        assertEquals(null, result.error)
    }

    @Test
    fun `selectByHint prefers enabled plugin IDs when fuzzy matches are multiple`() {
        val modules = listOf(
            TestModule(id = "acme-github-provider", name = "Acme GitHub Provider"),
            TestModule(id = "beta-github-provider", name = "Beta GitHub Provider")
        )

        val result = ModuleSelector.selectByHint(
            modules = modules,
            hint = "github",
            preferredIds = setOf("beta-github-provider"),
            typeName = "dynamic providers"
        )

        assertEquals("beta-github-provider", result.module?.id)
        assertEquals(null, result.error)
    }

    @Test
    fun `selectByHint falls back to single preferred module when hint has no text match`() {
        val modules = listOf(
            TestModule(id = "custom-provider", name = "Acme Enterprise Provider"),
            TestModule(id = "gitlab-provider", name = "GitLab Provider")
        )

        val result = ModuleSelector.selectByHint(
            modules = modules,
            hint = "gitlab",
            preferredIds = setOf("custom-provider"),
            typeName = "dynamic providers"
        )

        assertEquals("custom-provider", result.module?.id)
        assertEquals(null, result.error)
    }

    @Test
    fun `selectByHint resolves platform hint to unified platform service module`() {
        val modules = listOf(
            TestModule(id = "gitlab", name = "GitLab", type = ModuleType.PLATFORM_SERVICE),
            TestModule(id = "github", name = "GitHub", type = ModuleType.PLATFORM_SERVICE)
        )

        val result = ModuleSelector.selectByHint(
            modules = modules,
            hint = "gitlab",
            preferredIds = emptySet(),
            typeName = "platform service modules"
        )

        assertEquals("gitlab", result.module?.id)
        assertEquals(null, result.error)
    }

    @Test
    fun `selectByHint resolves github hint to unified github platform service module`() {
        val modules = listOf(
            TestModule(id = "gitlab", name = "GitLab", type = ModuleType.PLATFORM_SERVICE),
            TestModule(id = "github", name = "GitHub", type = ModuleType.PLATFORM_SERVICE)
        )

        val result = ModuleSelector.selectByHint(
            modules = modules,
            hint = "github",
            preferredIds = emptySet(),
            typeName = "platform service modules"
        )

        assertEquals("github", result.module?.id)
        assertEquals(null, result.error)
    }

    @Test
    fun `selectByHint reports mismatch when multiple preferred modules do not match hint`() {
        val modules = listOf(
            TestModule(id = "custom-provider-a", name = "Acme Provider A"),
            TestModule(id = "custom-provider-b", name = "Acme Provider B"),
            TestModule(id = "gitlab-provider", name = "GitLab Provider")
        )

        val result = ModuleSelector.selectByHint(
            modules = modules,
            hint = "gitlab",
            preferredIds = setOf("custom-provider-a", "custom-provider-b"),
            typeName = "dynamic providers"
        )

        assertEquals(null, result.module)
        val error1 = checkNotNull(result.error)
        assertTrue(error1.contains("No dynamic providers match 'gitlab' among preferred modules"))
    }

    @Test
    fun `selectByHint returns ambiguity error when multiple fuzzy matches remain`() {
        val modules = listOf(
            TestModule(id = "team-github-provider", name = "Team GitHub Provider"),
            TestModule(id = "backup-github-provider", name = "Backup GitHub Provider")
        )

        val result = ModuleSelector.selectByHint(
            modules = modules,
            hint = "github",
            preferredIds = emptySet(),
            typeName = "dynamic providers"
        )

        assertEquals(null, result.module)
        val error2 = checkNotNull(result.error)
        assertTrue(error2.contains("Multiple dynamic providers"))
    }

    @Test
    fun `selectByExactKey chooses preferred module when keys match`() {
        val modules = listOf(
            TestModule(id = "json-reporter-a", name = "JSON Reporter A", key = "json"),
            TestModule(id = "json-reporter-b", name = "JSON Reporter B", key = "json")
        )

        val result = ModuleSelector.selectByExactKey(
            modules = modules,
            key = "JSON",
            preferredIds = setOf("json-reporter-b"),
            typeName = "reporter modules",
            keyName = "format",
            keySelector = { it.key }
        )

        assertEquals("json-reporter-b", result.module?.id)
        assertEquals(null, result.error)
    }

    @Test
    fun `selectByExactKey returns ambiguity error without preference`() {
        val modules = listOf(
            TestModule(id = "json-reporter-a", name = "JSON Reporter A", key = "json"),
            TestModule(id = "json-reporter-b", name = "JSON Reporter B", key = "json")
        )

        val result = ModuleSelector.selectByExactKey(
            modules = modules,
            key = "json",
            preferredIds = emptySet(),
            typeName = "reporter modules",
            keyName = "format",
            keySelector = { it.key }
        )

        assertEquals(null, result.module)
        val error3 = checkNotNull(result.error)
        assertTrue(error3.contains("Multiple reporter modules match format"))
    }
}
