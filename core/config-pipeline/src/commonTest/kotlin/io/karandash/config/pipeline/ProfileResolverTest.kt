package io.karandash.config.pipeline

import io.karandash.api.Module
import io.karandash.api.ModuleRegistry
import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import io.karandash.api.errors.ConfigException
import io.karandash.api.module.CliCommandModule
import io.karandash.api.module.PlatformServiceModule
import io.karandash.api.module.ProfileModule
import io.karandash.api.module.ReporterModule
import io.karandash.api.module.RuleModule
import io.karandash.api.support.mapToJsonElement
import io.karandash.config.Config
import io.karandash.config.PolicyConfig
import io.karandash.config.ProfileOverrides
import io.karandash.config.ProfileRef
import io.karandash.config.RuleExecutionMeta
import io.karandash.config.RulesConfig
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProfileResolverTest {
    private class StubProfile(
        override val profileId: String,
        private val rules: Map<String, JsonElement>,
        private val reporters: Map<String, JsonElement> = emptyMap(),
        private val policies: List<JsonElement> = emptyList(),
    ) : ProfileModule {
        override val id = "io.karandash.profile.$profileId"
        override val name = "Test Profile"
        override val version = "1.0.0"
        override val summary = "Test profile"

        override suspend fun initialize(context: PluginContext): Boolean = true

        override suspend fun ruleConfigs(): Map<String, JsonElement> = rules

        override suspend fun reporterConfigs(): Map<String, JsonElement> = reporters

        override suspend fun policies(): List<JsonElement> = policies
    }

    private fun registryWith(profiles: List<ProfileModule>): ModuleRegistry =
        object : ModuleRegistry {
            override suspend fun register(module: Module) = Unit

            override suspend fun unregister(id: String): Boolean = false

            override fun get(id: String): Module? = null

            override fun getByType(type: ModuleType): List<Module> = if (type == ModuleType.PROFILE) profiles else emptyList()

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

    @Test
    fun `profile rules are merged into config`() {
        runTest {
            val profile = StubProfile(
                profileId = "test-profile",
                rules = mapOf(
                    "titleFormat" to mapToJsonElement(mapOf("pattern" to "^feat:.*")),
                    "draftPrevention" to mapToJsonElement(mapOf("blockDraft" to true)),
                ),
            )
            val registry = registryWith(listOf(profile))
            val config = Config()

            val result = ProfileResolver(registry).apply(config, ProfileRef("test-profile"))

            assertEquals(2, result.rules.ruleConfigs.size)
            assertEquals(
                "^feat:.*",
                result.rules.ruleConfigs["titleFormat"]
                    ?.jsonObject
                    ?.get("pattern")
                    ?.jsonPrimitive
                    ?.content
            )
            assertEquals(
                true,
                result.rules.ruleConfigs["draftPrevention"]
                    ?.jsonObject
                    ?.get("blockDraft")
                    ?.jsonPrimitive
                    ?.boolean
            )
        }
    }

    @Test
    fun `user rules take priority over profile rules`() {
        runTest {
            val profile = StubProfile(
                profileId = "test-profile",
                rules = mapOf(
                    "titleFormat" to mapToJsonElement(mapOf("pattern" to "^feat:.*", "minLength" to 10)),
                    "draftPrevention" to mapToJsonElement(mapOf("blockDraft" to true)),
                ),
            )
            val registry = registryWith(listOf(profile))
            val config = Config(
                rules = RulesConfig(
                    ruleConfigs = mapOf(
                        "titleFormat" to JsonObject(mapOf("pattern" to JsonPrimitive("^fix:.*"), "maxLength" to JsonPrimitive(72))),
                    ),
                ),
            )

            val result = ProfileResolver(registry).apply(config, ProfileRef("test-profile"))

            assertEquals(
                "^fix:.*",
                result.rules.ruleConfigs["titleFormat"]
                    ?.jsonObject
                    ?.get("pattern")
                    ?.jsonPrimitive
                    ?.content
            )
            assertEquals(
                72,
                result.rules.ruleConfigs["titleFormat"]
                    ?.jsonObject
                    ?.get("maxLength")
                    ?.jsonPrimitive
                    ?.int
            )
            assertEquals(
                true,
                result.rules.ruleConfigs["draftPrevention"]
                    ?.jsonObject
                    ?.get("blockDraft")
                    ?.jsonPrimitive
                    ?.boolean
            )
        }
    }

    @Test
    fun `profile overrides layer between profile baseline and user rules`() {
        runTest {
            val profile = StubProfile(
                profileId = "test-profile",
                rules = mapOf(
                    "titleFormat" to mapToJsonElement(mapOf("pattern" to "^feat:.*", "minLength" to 5)),
                ),
            )
            val registry = registryWith(listOf(profile))
            val config = Config()
            val ref = ProfileRef(
                id = "test-profile",
                overrides = ProfileOverrides(
                    rules = mapOf(
                        "titleFormat" to JsonObject(mapOf("minLength" to JsonPrimitive(20))),
                    ),
                ),
            )

            val result = ProfileResolver(registry).apply(config, ref)

            assertEquals(
                "^feat:.*",
                result.rules.ruleConfigs["titleFormat"]
                    ?.jsonObject
                    ?.get("pattern")
                    ?.jsonPrimitive
                    ?.content
            )
            assertEquals(
                20,
                result.rules.ruleConfigs["titleFormat"]
                    ?.jsonObject
                    ?.get("minLength")
                    ?.jsonPrimitive
                    ?.int
            )
        }
    }

    @Test
    fun `unknown profile ID throws ConfigException`() {
        runTest {
            val registry = registryWith(emptyList())
            val config = Config()

            val ex = assertFailsWith<ConfigException> {
                ProfileResolver(registry).apply(config, ProfileRef("nonexistent"))
            }
            assertTrue(ex.message!!.contains("nonexistent"))
        }
    }

    @Test
    fun `profile policies run before the user policies with kebab module ids normalized`() {
        runTest {
            val profilePolicy = mapToJsonElement(
                mapOf(
                    "when" to "commit_count > 30",
                    "name" to "integration-mr",
                    "modules" to listOf("commit-body"),
                    "severity" to "warning",
                ),
            )
            val registry = registryWith(listOf(StubProfile("test-profile", emptyMap(), policies = listOf(profilePolicy))))
            val userPolicy = PolicyConfig(condition = "is_draft", name = "mine")
            val config = Config(policies = listOf(userPolicy))

            val result = ProfileResolver(registry).apply(config, ProfileRef("test-profile"))

            assertEquals(listOf("integration-mr", "mine"), result.policies?.map { it.name })
            assertEquals(listOf("commitBody"), result.policies?.first()?.modules)
            val withoutPolicies = registryWith(listOf(StubProfile("test-profile", emptyMap())))
            assertEquals(listOf(userPolicy), ProfileResolver(withoutPolicies).apply(config, ProfileRef("test-profile")).policies)
        }
    }

    @Test
    fun `allProfiles returns sorted list`() {
        val profiles = listOf(
            StubProfile("z-profile", emptyMap()),
            StubProfile("a-profile", emptyMap()),
        )
        val registry = registryWith(profiles)

        val result = ProfileResolver(registry).allProfiles()

        assertEquals("a-profile", result[0].profileId)
        assertEquals("z-profile", result[1].profileId)
    }

    @Test
    fun `user rule deep-merges over the profile keeping profile keys it does not set`() {
        runTest {
            val profile = StubProfile(
                profileId = "p",
                rules = mapOf("sizeLimits" to mapToJsonElement(mapOf("maxLines" to 500, "maxCommits" to 10))),
            )
            val config = Config(
                rules = RulesConfig(ruleConfigs = mapOf("sizeLimits" to JsonObject(mapOf("maxLines" to JsonPrimitive(50))))),
            )

            val merged = ProfileResolver(registryWith(listOf(profile)))
                .apply(config, ProfileRef("p"))
                .rules.ruleConfigs
                .getValue("sizeLimits")
                .jsonObject

            assertEquals(50, merged.getValue("maxLines").jsonPrimitive.int)
            assertEquals(10, merged.getValue("maxCommits").jsonPrimitive.int)
        }
    }

    @Test
    fun `profile wins over rules inherited only through extends`() {
        runTest {
            val profile = StubProfile("p", mapOf("sizeLimits" to mapToJsonElement(mapOf("maxLines" to 500))))
            val config = Config(
                rules = RulesConfig(
                    ruleConfigs = mapOf(
                        "sizeLimits" to JsonObject(mapOf("maxLines" to JsonPrimitive(50), "maxCommits" to JsonPrimitive(3))),
                    ),
                ),
            )

            val merged = ProfileResolver(registryWith(listOf(profile)))
                .apply(config, ProfileRef("p"), inheritedRuleIds = setOf("sizeLimits"))
                .rules.ruleConfigs
                .getValue("sizeLimits")
                .jsonObject

            assertEquals(500, merged.getValue("maxLines").jsonPrimitive.int)
            assertEquals(3, merged.getValue("maxCommits").jsonPrimitive.int)
        }
    }

    @Test
    fun `profile severity applies only to profile rules without their own severity`() {
        runTest {
            val profile = StubProfile(
                "p",
                mapOf("conflict" to JsonObject(emptyMap()), "labels" to JsonObject(emptyMap())),
            )
            val config = Config(
                rules = RulesConfig(ruleMeta = mapOf("labels" to RuleExecutionMeta(severity = "info"))),
            )

            val meta = ProfileResolver(registryWith(listOf(profile)))
                .apply(config, ProfileRef("p", severityOverride = "warning"))
                .rules.ruleMeta

            assertEquals("warning", meta["conflict"]?.severity)
            assertEquals("info", meta["labels"]?.severity)
            assertEquals(null, meta["titleFormat"]?.severity)
        }
    }

    @Test
    fun `profile severity applies to a rule the profile ref overrides add`() {
        runTest {
            val profile = StubProfile("p", mapOf("conflict" to JsonObject(emptyMap())))
            val ref = ProfileRef(
                "p",
                severityOverride = "warning",
                overrides = ProfileOverrides(rules = mapOf("size-limits" to JsonObject(emptyMap()))),
            )

            val meta = ProfileResolver(registryWith(listOf(profile))).apply(Config(), ref).rules.ruleMeta

            assertEquals("warning", meta["sizeLimits"]?.severity)
            assertEquals("warning", meta["conflict"]?.severity)
        }
    }

    /** A severity the profile declares is lifted to rule meta; user and ref severity still win. */
    @Test
    fun `profile declared severity is lifted and overridable`() =
        runTest {
            val declared = JsonObject(mapOf("@severity" to JsonPrimitive("warning"), "minLength" to JsonPrimitive(3)))
            val profile = StubProfile("p", mapOf("titleFormat" to declared, "labels" to declared))
            val config = Config(rules = RulesConfig(ruleMeta = mapOf("labels" to RuleExecutionMeta(severity = "info"))))
            val resolver = ProfileResolver(registryWith(listOf(profile)))

            val resolved = resolver.apply(config, ProfileRef("p"))
            assertEquals("warning", resolved.rules.ruleMeta["titleFormat"]?.severity)
            assertEquals("info", resolved.rules.ruleMeta["labels"]?.severity)
            assertEquals(
                null,
                resolved.rules.ruleConfigs
                    .getValue("titleFormat")
                    .jsonObject["@severity"]
            )
            assertEquals(
                3,
                resolved.rules.ruleConfigs
                    .getValue("titleFormat")
                    .jsonObject
                    .getValue("minLength")
                    .jsonPrimitive.int
            )

            val overridden = resolver.apply(Config(), ProfileRef("p", severityOverride = "error"))
            assertEquals("error", overridden.rules.ruleMeta["titleFormat"]?.severity)
        }

    @Test
    fun `profile reporterConfigs apply when the config has no reporters block`() =
        runTest {
            val reporters = mapOf("json" to JsonObject(mapOf("pretty" to JsonPrimitive(true))))
            val registry = registryWith(listOf(StubProfile("p", emptyMap(), reporters)))

            assertEquals(reporters, ProfileResolver(registry).apply(Config(), ProfileRef("p")).reporters)
        }

    @Test
    fun `a reporters block replaces the profile reporterConfigs`() =
        runTest {
            val own = mapOf("sarif" to JsonObject(emptyMap()))
            val registry = registryWith(listOf(StubProfile("p", emptyMap(), mapOf("json" to JsonObject(emptyMap())))))

            assertEquals(own, ProfileResolver(registry).apply(Config(reporters = own), ProfileRef("p")).reporters)
        }

    private val kebabProfile = StubProfile(
        "kebab",
        mapOf(
            "title-length" to JsonObject(mapOf("max" to JsonPrimitive(50), "min" to JsonPrimitive(5))),
            "size-limits" to JsonObject(mapOf("maxLines" to JsonPrimitive(400))),
        ),
    )

    @Test
    fun `kebab profile ids merge with the user's camelCase rule keys instead of duplicating them`() =
        runTest {
            val config = Config(
                rules = RulesConfig(ruleConfigs = mapOf("titleLength" to JsonObject(mapOf("max" to JsonPrimitive(72))))),
            )

            val rules = ProfileResolver(registryWith(listOf(kebabProfile)))
                .apply(config, ProfileRef("kebab", severityOverride = "warning"))
                .rules

            assertEquals(setOf("titleLength", "sizeLimits"), rules.ruleConfigs.keys)
            val title = rules.ruleConfigs.getValue("titleLength").jsonObject
            assertEquals(72, title.getValue("max").jsonPrimitive.int, "user value wins")
            assertEquals(5, title.getValue("min").jsonPrimitive.int, "profile default kept")
            assertEquals(setOf("titleLength", "sizeLimits"), rules.ruleMeta.keys)
        }

    @Test
    fun `profile overrides keyed in any spelling land on the canonical rule key`() =
        runTest {
            val overrides = ProfileOverrides(
                rules = mapOf("Size_Limits" to JsonObject(mapOf("maxLines" to JsonPrimitive(900)))),
            )

            val rules = ProfileResolver(registryWith(listOf(kebabProfile)))
                .apply(Config(), ProfileRef("kebab", overrides = overrides))
                .rules

            assertEquals(setOf("titleLength", "sizeLimits"), rules.ruleConfigs.keys)
            assertEquals(
                900,
                rules.ruleConfigs
                    .getValue("sizeLimits")
                    .jsonObject
                    .getValue("maxLines")
                    .jsonPrimitive.int
            )
        }
}
