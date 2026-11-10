package io.karandash.cli.templates

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PresetRegistryTest {
    @BeforeTest
    fun setup() {
        PresetRegistry.reset()
        PresetRegistry.initialize()
    }

    @AfterTest
    fun cleanup() {
        PresetRegistry.reset()
    }

    @Test
    fun `test all base templates load`() {
        val minimal = PresetRegistry.getBaseTemplate("minimal")
        val standard = PresetRegistry.getBaseTemplate("standard")
        val strict = PresetRegistry.getBaseTemplate("strict")

        assertNotNull(minimal, "minimal template should load")
        assertNotNull(standard, "standard template should load")
        assertNotNull(strict, "strict template should load")

        // Verify they contain expected structure
        assertTrue(minimal.containsKey("rules"))
        assertTrue(standard.containsKey("rules"))
        assertTrue(strict.containsKey("rules"))
    }

    @Test
    fun `test all platform templates load`() {
        val github = PresetRegistry.getPlatformTemplate("github")
        val gitlab = PresetRegistry.getPlatformTemplate("gitlab")
        val bitbucket = PresetRegistry.getPlatformTemplate("bitbucket")
        val azureDevops = PresetRegistry.getPlatformTemplate("azure-devops")

        assertNotNull(github, "github template should load")
        assertNotNull(gitlab, "gitlab template should load")
        assertNotNull(bitbucket, "bitbucket template should load")
        assertNotNull(azureDevops, "azure-devops template should load")

        // Verify platform-specific structure
        assertTrue(github.containsKey("platform"))
        assertTrue(gitlab.containsKey("platform"))
    }

    @Test
    fun `test template not found returns null`() {
        val missing = PresetRegistry.getBaseTemplate("nonexistent")
        assertNull(missing, "Non-existent template should return null")

        val missingPlatform = PresetRegistry.getPlatformTemplate("unknown-platform")
        assertNull(missingPlatform, "Non-existent platform should return null")
    }

    @Test
    fun `test listTemplates returns correct counts`() {
        val baseTemplates = PresetRegistry.listBaseTemplates()
        val platformTemplates = PresetRegistry.listPlatformTemplates()

        assertEquals(3, baseTemplates.size, "Should have 3 base templates")
        assertTrue(baseTemplates.containsAll(listOf("minimal", "standard", "strict")))

        assertEquals(4, platformTemplates.size, "Should have 4 platform templates")
        assertTrue(platformTemplates.containsAll(listOf("github", "gitlab", "bitbucket", "azure-devops")))
    }

    @Test
    fun `test list results are sorted`() {
        val baseTemplates = PresetRegistry.listBaseTemplates()
        assertEquals(baseTemplates.sorted(), baseTemplates, "Base templates should be sorted")

        val platformTemplates = PresetRegistry.listPlatformTemplates()
        assertEquals(platformTemplates.sorted(), platformTemplates, "Platform templates should be sorted")
    }

    @Test
    fun `test initialize is idempotent`() {
        PresetRegistry.initialize()
        val firstCall = PresetRegistry.listBaseTemplates()

        PresetRegistry.initialize()
        val secondCall = PresetRegistry.listBaseTemplates()

        assertEquals(firstCall, secondCall, "Multiple initializations should yield same result")
    }

    @Test
    fun `test templates contain valid YAML structure`() {
        val standard = PresetRegistry.getBaseTemplate("standard")
        assertNotNull(standard)

        // Check version field
        assertTrue(standard.containsKey("version"))
        val version = standard["version"]
        assertTrue(version is String)

        // Check rules structure
        assertTrue(standard.containsKey("rules"))
        val rules = standard["rules"]
        assertTrue(rules is Map<*, *>)
    }
}
