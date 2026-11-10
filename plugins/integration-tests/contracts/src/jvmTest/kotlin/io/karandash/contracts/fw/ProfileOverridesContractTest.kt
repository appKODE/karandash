package io.karandash.contracts.fw

import io.karandash.api.ModuleRegistryImpl
import io.karandash.config.ProfileRef
import io.karandash.config.pipeline.ProfileResolver
import io.karandash.contracts.support.FwStubProfile
import io.karandash.contracts.support.createFwTempDir
import io.karandash.contracts.support.parseYamlConfig
import io.karandash.contracts.support.runFwPipeline
import io.karandash.validators.SizeLimitsRuleModule
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `profile-overrides:` is never parsed by `ConfigMapParser.parseFullConfigObj`,
 * `--profile` replaces the config's `profile:` block (dropping its overrides) in `ConfigLoader`, and
 * `ProfileResolver` replaces a profile rule config with the user's instead of deep-merging.
 */
class ProfileOverridesContractTest {
    @Test
    fun `profile-overrides block from YAML reaches Config profileOverrides`() =
        runTest {
            val config = parseYamlConfig(
                """
                profile-overrides:
                  title:
                    max-length: 50
                """,
            )
            assertEquals(50, config.profileOverrides.title.maxLength, "profile-overrides never parsed")
        }

    @Test
    fun `user rule config deep-merges over profile rule config`() =
        runTest {
            val profile = FwStubProfile("fwmerge", mapOf(SizeLimitsRuleModule.RULE_ID to sizeLimits(maxLines = 500, maxCommits = 10)))
            val registry = ModuleRegistryImpl().also { it.register(profile) }
            val user = parseYamlConfig(
                """
                rules:
                  size-limits:
                    max-lines: 100
                """,
            )
            val merged = ProfileResolver(registry).apply(user, ProfileRef(id = "fwmerge"))
            val sizeLimits = merged.rules.ruleConfigs[SizeLimitsRuleModule.RULE_ID]
            assertEquals(10, sizeLimits.intField("maxCommits"), "user maxLines replaced the profile block; maxCommits lost")
        }

    @Test
    fun `cli profile flag keeps config profile overrides`() =
        runTest {
            val profile = FwStubProfile("fwcli", mapOf(SizeLimitsRuleModule.RULE_ID to sizeLimits(maxLines = 1000, maxCommits = 10)))
            val run = runFwPipeline(
                dir = createFwTempDir(),
                configYaml = """
                    profile:
                      id: fwcli
                      overrides:
                        rules:
                          size-limits:
                            max-lines: 5
                """,
                extraModules = listOf(profile),
            ) { it.copy(profileId = "fwcli") }
            val config = run.result.getOrThrow().config
            assertEquals(
                5,
                config.rules.ruleConfigs[SizeLimitsRuleModule.RULE_ID].intField("maxLines"),
                "--profile dropped `profile.overrides` from config",
            )
        }

    private fun sizeLimits(
        maxLines: Int,
        maxCommits: Int,
    ): JsonObject = JsonObject(mapOf("maxLines" to JsonPrimitive(maxLines), "maxCommits" to JsonPrimitive(maxCommits)))

    private fun JsonElement?.intField(key: String): Int? = ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.intOrNull
}
