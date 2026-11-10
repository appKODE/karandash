package io.karandash.config.pipeline

import io.karandash.api.Module
import io.karandash.api.ModuleRegistry
import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import io.karandash.api.module.CliCommandModule
import io.karandash.api.module.PlatformServiceModule
import io.karandash.api.module.ProfileModule
import io.karandash.api.module.ReporterModule
import io.karandash.api.module.RuleModule
import io.karandash.api.support.mapToJsonElement
import io.karandash.config.Config
import io.karandash.config.ProfileOverridesConfig
import io.karandash.config.ProfileRef
import io.karandash.config.RulesConfig
import io.karandash.config.SizeOverridesConfig
import io.karandash.config.TitleOverridesConfig
import io.karandash.logger.Logger
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `profile-overrides` thresholds are merged into the rule configs a profile contributes. */
class ProfileOverridesKeyBehaviourTest {
    private class StubProfile(
        private val rules: Map<String, JsonElement>,
    ) : ProfileModule {
        override val profileId = "p"
        override val id = "io.karandash.profile.p"
        override val name = "Test Profile"
        override val version = "1.0.0"
        override val summary = "Test profile"

        override suspend fun initialize(context: PluginContext): Boolean = true

        override suspend fun ruleConfigs(): Map<String, JsonElement> = rules
    }

    private fun registryWith(profile: ProfileModule): ModuleRegistry =
        object : ModuleRegistry {
            override suspend fun register(module: Module) = Unit

            override suspend fun unregister(id: String): Boolean = false

            override fun get(id: String): Module? = null

            override fun getByType(type: ModuleType): List<Module> = if (type == ModuleType.PROFILE) listOf(profile) else emptyList()

            override fun ruleModules(): List<RuleModule> = emptyList()

            override fun platformServices(): List<PlatformServiceModule> = emptyList()

            override fun reporters(): List<ReporterModule> = emptyList()

            override fun registeredIds(): Set<String> = emptySet()

            override fun hasRule(ruleId: String): Boolean = false

            override fun findRuleHandler(ruleId: String): RuleModule? = null

            override fun findByAlias(alias: String): PlatformServiceModule? = null

            override fun allAliases(): Map<String, PlatformServiceModule> = emptyMap()

            override fun aliasCollisions(): List<String> = emptyList()

            override fun cliCommandModules(): List<CliCommandModule> = emptyList()

            override fun aiProviders(): List<io.karandash.api.ai.AiProviderModule> = emptyList()

            override fun findAiProvider(providerId: String): io.karandash.api.ai.AiProviderModule? = null

            override fun aiInterceptors(): List<io.karandash.api.ai.AiInterceptorModule> = emptyList()
        }

    private suspend fun resolved(overrides: ProfileOverridesConfig): Map<String, JsonElement> {
        val profile = StubProfile(
            mapOf(
                "titleFormat" to mapToJsonElement(mapOf("minLength" to 1, "maxLength" to 200)),
                "commitMessages" to mapToJsonElement(mapOf("subject" to mapOf("maxLength" to 300))),
                "sizeLimits" to mapToJsonElement(mapOf("maxLines" to 9999, "maxCommits" to 99)),
            ),
        )
        return ProfileResolver(registryWith(profile)).apply(Config(profileOverrides = overrides), ProfileRef("p")).rules.ruleConfigs
    }

    private fun Map<String, JsonElement>.number(
        rule: String,
        key: String,
    ): Int =
        getValue(rule)
            .jsonObject
            .getValue(key)
            .jsonPrimitive.int

    @Test
    fun `profileOverrides title minLength 10 and maxLength 72 replace the profile title thresholds`() =
        runTest {
            val rules = resolved(ProfileOverridesConfig(title = TitleOverridesConfig(minLength = 10, maxLength = 72)))
            assertEquals(10, rules.number("titleFormat", "minLength"))
            assertEquals(72, rules.number("titleFormat", "maxLength"))
        }

    @Test
    fun `profileOverrides title maxSubjectLength 50 replaces the commit subject threshold`() =
        runTest {
            val rules = resolved(ProfileOverridesConfig(title = TitleOverridesConfig(maxSubjectLength = 50)))
            assertEquals(
                50,
                rules
                    .getValue("commitMessages")
                    .jsonObject
                    .getValue("subject")
                    .jsonObject
                    .getValue("maxLength")
                    .jsonPrimitive.int
            )
        }

    @Test
    fun `profileOverrides size maxLines 800 and maxCommits 7 replace the profile size thresholds`() =
        runTest {
            val rules = resolved(ProfileOverridesConfig(size = SizeOverridesConfig(maxLines = 800, maxCommits = 7)))
            assertEquals(800, rules.number("sizeLimits", "maxLines"))
            assertEquals(7, rules.number("sizeLimits", "maxCommits"))
        }

    @Test
    fun `without profileOverrides the profile thresholds stay`() =
        runTest {
            val rules = resolved(ProfileOverridesConfig())
            assertEquals(200, rules.number("titleFormat", "maxLength"))
            assertEquals(9999, rules.number("sizeLimits", "maxLines"))
        }

    private class RecordingLogger : Logger {
        val warnings = mutableListOf<String>()

        override fun debug(message: String) = Unit

        override fun info(message: String) = Unit

        override fun warn(message: String) {
            warnings += message
        }

        override fun error(message: String) = Unit
    }

    @Test
    fun `profileOverrides for a rule the profile does not configure is ignored with a warning`() =
        runTest {
            val logger = RecordingLogger()
            val profile = StubProfile(mapOf("titleFormat" to mapToJsonElement(mapOf("minLength" to 1))))
            val overrides = ProfileOverridesConfig(size = SizeOverridesConfig(maxLines = 800))
            val rules = ProfileResolver(registryWith(profile), logger)
                .apply(Config(profileOverrides = overrides), ProfileRef("p"))
                .rules.ruleConfigs

            assertFalse("sizeLimits" in rules, rules.keys.toString())
            assertEquals(
                listOf("profile-overrides.size ignored: profile 'p' does not configure rule 'sizeLimits'"),
                logger.warnings,
            )
        }

    @Test
    fun `profileOverrides merges into a rule the user configures`() =
        runTest {
            val logger = RecordingLogger()
            val profile = StubProfile(mapOf("titleFormat" to mapToJsonElement(mapOf("minLength" to 1))))
            val config = Config(
                rules = RulesConfig(ruleConfigs = mapOf("sizeLimits" to mapToJsonElement(mapOf("maxCommits" to 5)))),
                profileOverrides = ProfileOverridesConfig(size = SizeOverridesConfig(maxLines = 800)),
            )
            val rules = ProfileResolver(registryWith(profile), logger).apply(config, ProfileRef("p")).rules.ruleConfigs

            assertEquals(800, rules.number("sizeLimits", "maxLines"))
            assertEquals(5, rules.number("sizeLimits", "maxCommits"))
            assertTrue(logger.warnings.isEmpty(), logger.warnings.toString())
        }
}
