package io.karandash.plugins.ai

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for [AiPipelineExtension.resolveEnabledModuleIds].
 */
class AiPipelineExtensionEnablementTest {
    @Test
    fun `null config returns empty set`() {
        val result = AiPipelineExtension.resolveEnabledModuleIds(null, "reports")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `config without ai block returns empty set`() {
        val config = buildJsonObject { put("rules", buildJsonObject { }) }
        val result = AiPipelineExtension.resolveEnabledModuleIds(config, "reports")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `ai block without section returns empty set`() {
        val config = buildJsonObject {
            put("ai", buildJsonObject { put("provider", "openai") })
        }
        val result = AiPipelineExtension.resolveEnabledModuleIds(config, "reports")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `boolean true enables module`() {
        val config = buildJsonObject {
            put(
                "ai",
                buildJsonObject {
                    put(
                        "reports",
                        buildJsonObject {
                            put("smart-digest", true)
                        },
                    )
                },
            )
        }
        val result = AiPipelineExtension.resolveEnabledModuleIds(config, "reports")
        assertEquals(setOf("smart-digest"), result)
    }

    @Test
    fun `boolean false disables module`() {
        val config = buildJsonObject {
            put(
                "ai",
                buildJsonObject {
                    put(
                        "reports",
                        buildJsonObject {
                            put("smart-digest", false)
                        },
                    )
                },
            )
        }
        val result = AiPipelineExtension.resolveEnabledModuleIds(config, "reports")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `object without enabled key enables by presence`() {
        val config = buildJsonObject {
            put(
                "ai",
                buildJsonObject {
                    put(
                        "metrics",
                        buildJsonObject {
                            put(
                                "cognitive-load",
                                buildJsonObject { put("threshold", 0.8) },
                            )
                        },
                    )
                },
            )
        }
        val result = AiPipelineExtension.resolveEnabledModuleIds(config, "metrics")
        assertEquals(setOf("cognitive-load"), result)
    }

    @Test
    fun `object with enabled false disables module`() {
        val config = buildJsonObject {
            put(
                "ai",
                buildJsonObject {
                    put(
                        "reports",
                        buildJsonObject {
                            put(
                                "risk-heatmap",
                                buildJsonObject { put("enabled", false) },
                            )
                        },
                    )
                },
            )
        }
        val result = AiPipelineExtension.resolveEnabledModuleIds(config, "reports")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `mixed enabled and disabled modules`() {
        val config = buildJsonObject {
            put(
                "ai",
                buildJsonObject {
                    put(
                        "reports",
                        buildJsonObject {
                            put("smart-digest", true)
                            put("risk-heatmap", false)
                            put(
                                "dora-metrics",
                                buildJsonObject { put("enabled", true) },
                            )
                            put(
                                "team-scorecard",
                                buildJsonObject { put("enabled", false) },
                            )
                        },
                    )
                },
            )
        }
        val result = AiPipelineExtension.resolveEnabledModuleIds(config, "reports")
        assertEquals(setOf("smart-digest", "dora-metrics"), result)
    }

    @Test
    fun `direct ai block without wrapper works`() {
        val config = buildJsonObject {
            put("provider", "openai")
            put(
                "reports",
                buildJsonObject {
                    put("smart-digest", true)
                },
            )
        }
        val result = AiPipelineExtension.resolveEnabledModuleIds(config, "reports")
        assertEquals(setOf("smart-digest"), result)
    }

    @Test
    fun `camelCased keys from the normalized config chain resolve to kebab module ids`() {
        val config = buildJsonObject {
            put(
                "ai",
                buildJsonObject {
                    put("reports", buildJsonObject { put("cognitiveLoad", true) })
                },
            )
        }
        val result = AiPipelineExtension.resolveEnabledModuleIds(config, "reports")
        assertEquals(setOf("cognitive-load"), result)
    }
}
