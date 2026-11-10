package io.karandash.plugins.ai.core.config

import io.karandash.plugins.ai.core.AiPluginConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class AiConfigAliasesTest {
    private fun parse(json: String) = AiPluginConfig.fromPluginConfig(Json.parseToJsonElement(json))

    @Test
    fun `macros list form becomes macro definitions and applyMacros references every file`() {
        val config = parse(
            """{"macros": {"strict": [{"rule": "r1", "enforceSeverity": "error"}]}, "applyMacros": ["strict"]}""",
        )
        val rule = config.macroDefinitions
            .getValue("strict")
            .rules
            .getValue("r1")
            .jsonObject
        assertEquals(JsonPrimitive("error"), rule["enforceSeverity"])
        assertEquals(listOf(MacroReference(listOf("**"), "strict")), config.macroReferences)
    }

    @Test
    fun `environmentTracks become anchored tracks with overrides and disabled rules`() {
        val config = parse(
            """{"environmentTracks": [{"targetBranch": "main|master", "rules": {"aiSecurityScan": {"enabled": false}}}]}""",
        )
        val track = config.tracks.single()
        assertEquals("main|master", track.name)
        assertEquals("^(?:main|master)$", track.targetBranchPattern)
        assertEquals(listOf("ai-security-scan"), track.disableRules)
        assertEquals(setOf("aiSecurityScan"), track.ruleOverrides.keys)
    }

    @Test
    fun `teams rules is read as ruleOverrides`() {
        val config = parse("""{"teams": [{"name": "core", "rules": {"r1": "info"}}]}""")
        assertEquals(JsonPrimitive("info"), config.teams.single().ruleOverrides["r1"])
    }

    @Test
    fun `alias keys survive the top-level ai block split`() {
        val block = Json.parseToJsonElement("""{"applyMacros": ["m"], "environmentTracks": [{"targetBranch": "main"}]}""")
        val config = AiPluginConfig.fromPluginConfig(AiPluginConfig.topLevelAiBlockToPluginConfigElement(block))
        assertEquals(1, config.macroReferences.size)
        assertEquals(1, config.tracks.size)
    }
}
