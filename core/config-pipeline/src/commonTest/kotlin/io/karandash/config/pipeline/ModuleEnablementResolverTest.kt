package io.karandash.config.pipeline

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModuleEnablementResolverTest {
    private val resolver = ModuleEnablementResolver()

    @Test
    fun `boolean shorthand false disables module`() {
        val config = buildJsonObject {
            put(
                "rules",
                buildJsonObject {
                    put("draft-prevention", false)
                }
            )
        }
        val result = resolver.resolve(config)
        assertFalse(result.getValue("draftPrevention"))
    }

    @Test
    fun `boolean shorthand true enables module`() {
        val config = buildJsonObject {
            put(
                "rules",
                buildJsonObject {
                    put("draft-prevention", true)
                }
            )
        }
        val result = resolver.resolve(config)
        assertTrue(result.getValue("draftPrevention"))
    }

    @Test
    fun `explicit enabled false in object disables module`() {
        val config = buildJsonObject {
            put(
                "rules",
                buildJsonObject {
                    put(
                        "title-format",
                        buildJsonObject {
                            put("enabled", false)
                        }
                    )
                }
            )
        }
        val result = resolver.resolve(config)
        assertFalse(result.getValue("titleFormat"))
    }

    @Test
    fun `explicit enabled true in object enables module`() {
        val config = buildJsonObject {
            put(
                "rules",
                buildJsonObject {
                    put(
                        "title-format",
                        buildJsonObject {
                            put("enabled", true)
                        }
                    )
                }
            )
        }
        val result = resolver.resolve(config)
        assertTrue(result.getValue("titleFormat"))
    }

    @Test
    fun `object without enabled key enables module by presence`() {
        val config = buildJsonObject {
            put(
                "rules",
                buildJsonObject {
                    put(
                        "title-format",
                        buildJsonObject {
                            put("pattern", ".*")
                        }
                    )
                }
            )
        }
        val result = resolver.resolve(config)
        assertTrue(result.getValue("titleFormat"))
    }

    @Test
    fun `non-boolean primitive is ignored`() {
        val config = buildJsonObject {
            put(
                "rules",
                buildJsonObject {
                    put("title-format", "some-string")
                }
            )
        }
        val result = resolver.resolve(config)
        assertFalse(result.containsKey("titleFormat"))
    }

    @Test
    fun `resolves across multiple sections`() {
        val config = buildJsonObject {
            put(
                "rules",
                buildJsonObject {
                    put("draft-prevention", false)
                }
            )
            put(
                "reporters",
                buildJsonObject {
                    put(
                        "console",
                        buildJsonObject {
                            put("enabled", true)
                        }
                    )
                }
            )
            put(
                "platforms",
                buildJsonObject {
                    put("gitlab", true)
                }
            )
        }
        val result = resolver.resolve(config)
        assertEquals(3, result.size)
        assertFalse(result.getValue("draftPrevention"))
        assertTrue(result.getValue("console"))
        assertTrue(result.getValue("gitlab"))
    }

    @Test
    fun `empty config returns empty map`() {
        val config = JsonObject(emptyMap())
        val result = resolver.resolve(config)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `missing section is skipped`() {
        val config = buildJsonObject {
            put(
                "rules",
                buildJsonObject {
                    put("rule1", false)
                }
            )
            // no reporters, providers, etc.
        }
        val result = resolver.resolve(config)
        assertEquals(1, result.size)
    }

    @Test
    fun `isEnabled returns explicit value when present`() {
        val enabledModules = mapOf("rule1" to false)
        val module = object : io.karandash.api.Module {
            override val id = "rule1"
            override val name = "Rule 1"
            override val version = "1.0"
            override val type get() = io.karandash.api.ModuleType.RULE
            override val defaultEnabled = true

            override suspend fun initialize(context: io.karandash.api.PluginContext) = true
        }
        assertFalse(ModuleEnablementResolver.isEnabled("rule1", enabledModules, module))
    }

    @Test
    fun `resolveFromConfig resolves aiReports section`() {
        val config = io.karandash.config.Config(
            aiReports = mapOf(
                "smart-digest" to buildJsonObject { },
                "risk-heatmap" to buildJsonObject { put("enabled", false) },
            ),
        )
        val result = resolver.resolveFromConfig(config)
        assertTrue(result.getValue("smartDigest"))
        assertFalse(result.getValue("riskHeatmap"))
    }

    @Test
    fun `resolveFromConfig resolves aiMetrics section`() {
        val config = io.karandash.config.Config(
            aiMetrics = mapOf(
                "cognitive-load" to buildJsonObject { },
                "change-entropy" to buildJsonObject { put("enabled", true) },
            ),
        )
        val result = resolver.resolveFromConfig(config)
        assertTrue(result.getValue("cognitiveLoad"))
        assertTrue(result.getValue("changeEntropy"))
    }

    @Test
    fun `resolveFromConfig resolves enrichmentProviders section`() {
        val config = io.karandash.config.Config(
            enrichmentProviders = mapOf(
                "jira" to buildJsonObject { put("url", "https://jira.example.com") },
                "local-git" to buildJsonObject { put("enabled", false) },
            ),
        )
        val result = resolver.resolveFromConfig(config)
        assertTrue(result.getValue("jira"))
        assertFalse(result.getValue("localGit"))
    }

    @Test
    fun `resolveFromConfig skips null aiReports and aiMetrics`() {
        val config = io.karandash.config.Config()
        val result = resolver.resolveFromConfig(config)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `isEnabled falls back to Module defaultEnabled when no entry`() {
        val enabledModules = emptyMap<String, Boolean>()
        val module = object : io.karandash.api.Module {
            override val id = "rule1"
            override val name = "Rule 1"
            override val version = "1.0"
            override val type get() = io.karandash.api.ModuleType.RULE
            override val defaultEnabled = false

            override suspend fun initialize(context: io.karandash.api.PluginContext) = true
        }
        assertFalse(ModuleEnablementResolver.isEnabled("rule1", enabledModules, module))
    }
}
