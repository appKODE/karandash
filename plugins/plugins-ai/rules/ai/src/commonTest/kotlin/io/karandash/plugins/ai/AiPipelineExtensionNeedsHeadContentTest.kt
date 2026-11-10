@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.plugins.ai

import io.karandash.api.PluginContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AiPipelineExtensionNeedsHeadContentTest {
    private val extension = AiPipelineExtension(buildTestRegistry())

    private fun fullFileContext(mode: String? = null) =
        buildJsonObject {
            putJsonObject("contextQuality") {
                putJsonObject("fullFileContext") {
                    put("enabled", true)
                    mode?.let { put("mode", it) }
                }
            }
        }

    @Test
    fun `head content is fetched only for full review mode or full-file context`() {
        assertFalse(extension.needsHeadContent(null))
        assertFalse(extension.needsHeadContent(buildJsonObject { put("enabled", true) }))
        assertTrue(extension.needsHeadContent(buildJsonObject { put("reviewMode", "full") }))
        assertTrue(extension.needsHeadContent(fullFileContext()))
        assertFalse(extension.needsHeadContent(fullFileContext(mode = "diff-only")))
    }

    @Test
    fun `hunk-expansion on fetches head content`() {
        val on = buildJsonObject {
            putJsonObject("contextQuality") { putJsonObject("hunk-expansion") { put("enabled", true) } }
        }
        val qualityOff = buildJsonObject {
            putJsonObject("contextQuality") {
                put("enabled", false)
                putJsonObject("hunkExpansion") { put("enabled", true) }
            }
        }
        assertTrue(extension.needsHeadContent(on))
        assertFalse(extension.needsHeadContent(qualityOff))
    }

    @Test
    fun `symbol definitions on fetches head content`() {
        val on = buildJsonObject {
            putJsonObject("contextQuality") { putJsonObject("symbolDefinitions") { put("enabled", true) } }
        }
        val kebab = buildJsonObject {
            putJsonObject("contextQuality") { putJsonObject("symbol-definitions") { put("enabled", true) } }
        }
        assertTrue(extension.needsHeadContent(on))
        assertTrue(extension.needsHeadContent(kebab))
        assertFalse(extension.needsHeadContent(buildJsonObject {}))
    }

    private val defaultOnFeatures = listOf(
        "riskScoring",
        "descriptionQuality",
        "changeCategorization",
        "reviewComments",
        "similarIssues",
        "fixSuggestions",
    )

    private fun allAnalyzersOff(extra: JsonObjectBuilder.() -> Unit) =
        buildJsonObject {
            putJsonObject("features") { defaultOnFeatures.forEach { put(it, false) } }
            extra()
        }

    @Test
    fun `disabled AI fetches no head content even in full review mode`() {
        val config = allAnalyzersOff { put("reviewMode", "full") }

        assertFalse(extension.needsHeadContent(config))
    }

    @Test
    fun `enabled AI in full review mode fetches head content`() {
        val config = buildJsonObject {
            putJsonObject("features") { put("riskScoring", true) }
            put("reviewMode", "full")
        }

        assertTrue(extension.needsHeadContent(config))
    }

    @Test
    fun `disabled AI fetches no head content with full-file context`() {
        val config = allAnalyzersOff {
            putJsonObject("contextQuality") { putJsonObject("fullFileContext") { put("enabled", true) } }
        }

        assertFalse(extension.needsHeadContent(config))
    }

    @Test
    fun `the review mode env override turns head content on`() =
        runTest {
            val withEnv = AiPipelineExtension(buildTestRegistry())
            withEnv.initialize(
                object : PluginContext {
                    override val pluginConfig: JsonElement = JsonObject(emptyMap())

                    override fun env(name: String): String? = if (name == "KARANDASH_REVIEW_MODE") "full" else null

                    override fun log(message: String) {}

                    override fun warn(message: String) {}

                    override val hostVersion: String = "1.0.0-test"
                    override val workingDirectory: String = "/tmp/test"
                },
            )
            assertTrue(withEnv.needsHeadContent(buildJsonObject { put("enabled", true) }))
        }
}
