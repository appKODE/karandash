package io.karandash.profiles

import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import io.karandash.models.support.KarandashJson
import io.karandash.sdk.config.BlockMode
import io.karandash.validators.DraftConfig
import io.karandash.validators.FileChangesConfig
import io.karandash.validators.LabelsConfig
import io.karandash.validators.SecurityConfig
import io.karandash.validators.SizeLimitsConfig
import io.karandash.validators.TitleFormatConfig
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MinimalProfileModuleTest {
    private val module = MinimalProfileModule()

    private class TestContext : PluginContext {
        override val pluginConfig: JsonElement = JsonObject(emptyMap())
        override val hostVersion: String = "test"
        override val workingDirectory: String = "/tmp"

        override fun log(message: String) {}

        override fun warn(message: String) {}

        override fun env(name: String): String? = null
    }

    @Test
    fun profileIdMatchesExpected() {
        assertEquals("minimal", module.profileId)
    }

    @Test
    fun typeIsProfile() {
        assertEquals(ModuleType.PROFILE, module.type)
    }

    @Test
    fun ruleConfigsIsNotEmpty() {
        runTest {
            assertTrue(module.ruleConfigs().isNotEmpty())
        }
    }

    @Test
    fun initializeReturnsTrue() =
        runTest {
            assertTrue(module.initialize(TestContext()))
        }

    @Test
    fun summaryIsNotBlank() {
        assertTrue(module.summary.isNotBlank())
    }

    @Test
    fun ruleConfigsRoundTripThroughConfigSpec() {
        runTest {
            val configs = module.ruleConfigs()

            val titleConfig = KarandashJson.Default.decodeFromJsonElement(TitleFormatConfig.serializer(), configs["titleFormat"]!!)
            assertEquals(10, titleConfig.minLength)

            val draftConfig = KarandashJson.Default.decodeFromJsonElement(DraftConfig.serializer(), configs["draftPrevention"]!!)
            assertEquals(BlockMode.OFF, draftConfig.blockDraft)

            val sizeConfig = KarandashJson.Default.decodeFromJsonElement(SizeLimitsConfig.serializer(), configs["sizeLimits"]!!)
            assertEquals(500, sizeConfig.maxLines)
            assertEquals(20, sizeConfig.maxCommits)

            val fileChangesConfig = KarandashJson.Default.decodeFromJsonElement(FileChangesConfig.serializer(), configs["fileChanges"]!!)
            assertNotNull(fileChangesConfig)

            val securityConfig = KarandashJson.Default.decodeFromJsonElement(SecurityConfig.serializer(), configs["security"]!!)
            assertNotNull(securityConfig)

            val labelsConfig = KarandashJson.Default.decodeFromJsonElement(LabelsConfig.serializer(), configs["labels"]!!)
            assertNotNull(labelsConfig)
        }
    }

    /** The zero-config title regex accepts scope, build, ci and revert like the other profiles. */
    @Test
    fun `title pattern accepts scope and build ci revert`() =
        runTest {
            val pattern = KarandashJson.Default
                .decodeFromJsonElement(TitleFormatConfig.serializer(), module.ruleConfigs().getValue("titleFormat"))
                .pattern
            val regex = Regex(checkNotNull(pattern))

            listOf("feat(api): add x", "build: bump", "ci: cache", "revert: undo", "fix!: break").forEach {
                assertTrue(regex.matches(it), it)
            }
            assertTrue(!regex.matches("added stuff"))
        }

    /** A draft MR is not an error in the zero-config profile. */
    @Test
    fun `minimal does not block drafts`() =
        runTest {
            val draft = KarandashJson.Default
                .decodeFromJsonElement(DraftConfig.serializer(), module.ruleConfigs().getValue("draftPrevention"))
            assertEquals(BlockMode.OFF, draft.blockDraft)
        }

    /** Minimal runs every rule at warning severity. */
    @Test
    fun `minimal declares warning severity for every rule`() =
        runTest {
            module.ruleConfigs().forEach { (id, config) ->
                assertEquals("warning", (config as JsonObject)["@severity"]?.jsonPrimitive?.content, id)
            }
        }
}
