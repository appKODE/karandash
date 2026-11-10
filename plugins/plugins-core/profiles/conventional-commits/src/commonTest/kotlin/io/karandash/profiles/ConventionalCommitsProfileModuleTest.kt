package io.karandash.profiles

import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import io.karandash.models.support.KarandashJson
import io.karandash.sdk.config.BlockMode
import io.karandash.validators.CommitMessagesConfig
import io.karandash.validators.DraftConfig
import io.karandash.validators.SizeLimitsConfig
import io.karandash.validators.TitleFormatConfig
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConventionalCommitsProfileModuleTest {
    private val module = ConventionalCommitsProfileModule()

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
        assertEquals("conventional-commits", module.profileId)
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
            assertEquals(72, titleConfig.maxLength)
            assertTrue(titleConfig.pattern?.isNotBlank() == true)

            val commitConfig = KarandashJson.Default.decodeFromJsonElement(CommitMessagesConfig.serializer(), configs["commitMessages"]!!)
            assertEquals(72, commitConfig.maxLength)
            assertTrue(commitConfig.pattern!!.isNotBlank())

            val sizeConfig = KarandashJson.Default.decodeFromJsonElement(SizeLimitsConfig.serializer(), configs["sizeLimits"]!!)
            assertEquals(800, sizeConfig.maxLines)
            assertEquals(25, sizeConfig.maxCommits)

            val draftConfig = KarandashJson.Default.decodeFromJsonElement(DraftConfig.serializer(), configs["draftPrevention"]!!)
            assertEquals(BlockMode.FAIL, draftConfig.blockDraft)
        }
    }
}
