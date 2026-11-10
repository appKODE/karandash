package io.karandash.contracts.ai

import io.karandash.api.ModuleRegistryImpl
import io.karandash.contracts.support.parseYamlConfig
import io.karandash.plugins.ai.AiPipelineExtension
import io.karandash.plugins.ai.core.AiPluginConfig
import io.karandash.plugins.ai.core.rules.AiRuleConfig
import io.karandash.plugins.ai.core.rules.TruncationStrategy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Documented `ai:` keys must reach the typed AI config after the real
 * YAML -> ConfigMapParser (key normalization) -> AiPipelineExtension.computeRuleModuleConfig path.
 * Today several are dropped (not in `TOP_LEVEL_KEYS`), looked up by their kebab name after
 * camelCase normalization, or never assigned.
 *
 * Each parameter is one documented key; `responseLanguage` is a control that already works.
 */
@RunWith(Parameterized::class)
class AiConfigKeyPlumbingContractTest(
    private val case: KeyCase,
) {
    /** One documented `ai:` key, its YAML, and how to read it back from the plugin config. */
    class KeyCase(
        val key: String,
        val yaml: String,
        val expected: Any?,
        val read: (JsonElement) -> Any?,
    ) {
        override fun toString(): String = key
    }

    @Test
    fun `documented ai key reaches the typed AI config`() =
        runTest {
            val config = parseYamlConfig("ai:\n" + case.yaml.trimIndent().prependIndent("  "))
            val pluginCfg = requireNotNull(AiPipelineExtension(ModuleRegistryImpl()).computeRuleModuleConfig(config.ai)) {
                "setup: computeRuleModuleConfig returned null for ai block"
            }
            assertEquals(case.expected, case.read(pluginCfg), "ai.${case.key} was dropped or misread")
        }

    companion object {
        private fun rule(cfg: JsonElement) = AiRuleConfig.fromPluginConfig(cfg)

        private fun plugin(cfg: JsonElement) = AiPluginConfig.fromPluginConfig(cfg)

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): List<KeyCase> =
            listOf(
                KeyCase(
                    key = "size-policy.partial-review.maxFiles",
                    yaml = "size-policy:\n  partial-review:\n    maxFiles: 7",
                    expected = 7,
                ) { rule(it).partialReviewPolicy?.maxFiles },
                KeyCase(
                    key = "size-policy.partial-review.prioritize-types",
                    yaml = "size-policy:\n  partial-review:\n    prioritize-types: [\"*.kt\"]",
                    expected = listOf("*.kt"),
                ) { rule(it).partialReviewPolicy?.prioritizeTypes },
                KeyCase(
                    key = "contextQuality.findingValidator.strict",
                    yaml = "contextQuality:\n  findingValidator:\n    strict: false",
                    expected = false,
                ) { rule(it).contextQuality.findingValidator.strict },
                KeyCase(
                    key = "contextQuality.truncation.strategy",
                    yaml = "contextQuality:\n  truncation:\n    strategy: priority",
                    expected = TruncationStrategy.PRIORITY,
                ) { rule(it).contextQuality.truncation.strategy },
                KeyCase(
                    key = "context-lines",
                    yaml = "context-lines: 7",
                    expected = 7,
                ) { plugin(it).contextLines },
                KeyCase(
                    key = "conventionContext",
                    yaml = "conventionContext:\n  enabled: true\n  maxChars: 1234",
                    expected = true,
                ) { cfg -> (cfg as? JsonObject)?.containsKey("conventionContext") },
                KeyCase(
                    key = "ollama.allow-hosts",
                    yaml = "ollama:\n  allow-hosts: [gpu-box.lan]",
                    expected = listOf("gpu-box.lan"),
                ) { plugin(it).ollama.allowHosts },
                KeyCase(
                    key = "responseLanguage (control)",
                    yaml = "responseLanguage: de",
                    expected = "de",
                ) { rule(it).responseLanguage },
            )
    }
}
