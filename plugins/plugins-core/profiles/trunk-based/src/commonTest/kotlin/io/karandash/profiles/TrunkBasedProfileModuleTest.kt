package io.karandash.profiles

import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import io.karandash.models.support.KarandashJson
import io.karandash.sdk.config.BlockMode
import io.karandash.validators.BranchNamingConfig
import io.karandash.validators.DraftConfig
import io.karandash.validators.SizeLimitsConfig
import io.karandash.validators.TitleFormatConfig
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TrunkBasedProfileModuleTest {
    private val module = TrunkBasedProfileModule()

    private class TestContext : PluginContext {
        override val pluginConfig: kotlinx.serialization.json.JsonElement = kotlinx.serialization.json.JsonObject(emptyMap())
        override val hostVersion: String = "test"
        override val workingDirectory: String = "/tmp"

        override fun log(message: String) {}

        override fun warn(message: String) {}

        override fun env(name: String): String? = null
    }

    @Test
    fun profileIdMatchesExpected() {
        assertEquals("trunk-based", module.profileId)
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
            assertEquals(5, titleConfig.minLength)
            assertEquals(72, titleConfig.maxLength)

            val sizeConfig = KarandashJson.Default.decodeFromJsonElement(SizeLimitsConfig.serializer(), configs["sizeLimits"]!!)
            assertEquals(200, sizeConfig.maxLines)
            assertEquals(5, sizeConfig.maxCommits)

            val draftConfig = KarandashJson.Default.decodeFromJsonElement(DraftConfig.serializer(), configs["draftPrevention"]!!)
            assertEquals(BlockMode.FAIL, draftConfig.blockDraft)
            assertEquals(5, draftConfig.wipPatterns.size)

            val branchConfig = KarandashJson.Default.decodeFromJsonElement(BranchNamingConfig.serializer(), configs["branchNaming"]!!)
            assertTrue(branchConfig.pattern!!.isNotBlank())
        }
    }

    /** The trunk-based branch pattern is not a catch-all. */
    @Test
    fun `trunk-based branch pattern is meaningful`() =
        runTest {
            val branch = KarandashJson.Default.decodeFromJsonElement(
                BranchNamingConfig.serializer(),
                module.ruleConfigs().getValue("branchNaming")
            )
            val regex = Regex(checkNotNull(branch.pattern))
            listOf("main", "trunk", "fix-login-npe", "alice/short-lived").forEach { assertTrue(regex.matches(it), it) }
            listOf("Feature/Some Long Branch", "x".repeat(60), "").forEach { assertTrue(!regex.matches(it), it) }
        }

    /** Pin: trunk-based is not a no-op beyond its branch pattern: size, draft and title rules are active. */
    @Test
    fun `trunk-based enforces size draft and title beyond the branch pattern`() =
        runTest {
            val configs = module.ruleConfigs()
            val size = KarandashJson.Default.decodeFromJsonElement(SizeLimitsConfig.serializer(), configs.getValue("sizeLimits"))
            assertEquals(200, size.maxLines)
            assertEquals(5, size.maxCommits)
            assertEquals(
                BlockMode.FAIL,
                KarandashJson.Default.decodeFromJsonElement(DraftConfig.serializer(), configs.getValue("draftPrevention")).blockDraft,
            )
            assertEquals(
                5,
                KarandashJson.Default.decodeFromJsonElement(TitleFormatConfig.serializer(), configs.getValue("titleFormat")).minLength
            )
        }
}
