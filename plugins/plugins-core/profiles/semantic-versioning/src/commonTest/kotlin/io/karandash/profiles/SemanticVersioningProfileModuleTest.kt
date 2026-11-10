package io.karandash.profiles

import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import io.karandash.models.support.KarandashJson
import io.karandash.validators.CommitMessagesConfig
import io.karandash.validators.SizeLimitsConfig
import io.karandash.validators.TitleFormatConfig
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SemanticVersioningProfileModuleTest {
    private val module = SemanticVersioningProfileModule()

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
        assertEquals("semantic-versioning", module.profileId)
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
            assertEquals(30, sizeConfig.maxCommits)
        }
    }

    /** Semver is more than conventional commits: a breaking `!` commit must carry a BREAKING CHANGE body line. */
    @Test
    fun `semver requires a breaking change body for bang commits`() =
        runTest {
            val body = io.karandash.models.support.KarandashJson.Default.decodeFromJsonElement(
                io.karandash.validators.CommitBodyConfig
                    .serializer(),
                module.ruleConfigs().getValue("commitBody"),
            )
            assertTrue(Regex(checkNotNull(body.requiredWhenSubjectMatches)).containsMatchIn("feat(api)!: drop v1"))
            assertTrue(!Regex(checkNotNull(body.requiredWhenSubjectMatches)).containsMatchIn("feat: add"))
            assertTrue(Regex(checkNotNull(body.pattern)).containsMatchIn("BREAKING CHANGE: v1 removed"))
        }
}
