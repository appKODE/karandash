package io.karandash.config.pipeline

import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for AI reports/metrics and enrichment provider config parsing in [ConfigMapParser].
 */
class ConfigMapParserAiReportsMetricsTest {
    @Test
    fun `ai reports parsed from ai block`() {
        val data = mapOf<String, Any?>(
            "ai" to mapOf(
                "provider" to "openai",
                "reports" to mapOf(
                    "smart-digest" to true,
                    "risk-heatmap" to mapOf("enabled" to true),
                    "team-scorecard" to false,
                ),
            ),
        )
        val config = ConfigMapParser.parseFullConfig(data).config

        val reports = config.aiReports
        assertNotNull(reports)
        assertEquals(3, reports.size)
        // smart-digest: boolean true -> empty object (enabled by presence)
        assertTrue(reports.containsKey("smartDigest"))
        // risk-heatmap: object with enabled true
        assertTrue(reports.containsKey("riskHeatmap"))
        assertTrue(reports["riskHeatmap"]!!.jsonObject["enabled"]!!.jsonPrimitive.boolean)
        // team-scorecard: boolean false -> disabled module element
        assertTrue(reports.containsKey("teamScorecard"))
    }

    @Test
    fun `ai metrics parsed from ai block`() {
        val data = mapOf<String, Any?>(
            "ai" to mapOf(
                "provider" to "openai",
                "metrics" to mapOf(
                    "cognitive-load" to true,
                    "avg-findings" to mapOf("enabled" to true),
                    "hotspot-density" to false,
                ),
            ),
        )
        val config = ConfigMapParser.parseFullConfig(data).config

        val metrics = config.aiMetrics
        assertNotNull(metrics)
        assertEquals(3, metrics.size)
        assertTrue(metrics.containsKey("cognitiveLoad"))
        assertTrue(metrics.containsKey("avgFindings"))
        assertTrue(metrics.containsKey("hotspotDensity"))
    }

    @Test
    fun `ai block without reports or metrics returns null`() {
        val data = mapOf<String, Any?>(
            "ai" to mapOf(
                "provider" to "openai",
            ),
        )
        val config = ConfigMapParser.parseFullConfig(data).config

        assertNull(config.aiReports)
        assertNull(config.aiMetrics)
    }

    @Test
    fun `no ai block returns null for reports and metrics`() {
        val data = mapOf<String, Any?>(
            "rules" to mapOf(
                "title-format" to mapOf("pattern" to ".*"),
            ),
        )
        val config = ConfigMapParser.parseFullConfig(data).config

        assertNull(config.aiReports)
        assertNull(config.aiMetrics)
    }

    @Test
    fun `enrichment providers parsed from top-level enrichment block`() {
        val data = mapOf<String, Any?>(
            "enrichment" to mapOf(
                "jira" to mapOf(
                    "url" to "https://jira.example.com",
                    "token" to "test-token",
                ),
                "local-git" to true,
            ),
        )
        val config = ConfigMapParser.parseFullConfig(data).config

        val providers = config.enrichmentProviders
        assertNotNull(providers)
        assertEquals(2, providers.size)
        assertTrue(providers.containsKey("jira"))
        assertEquals(
            "https://jira.example.com",
            providers["jira"]!!.jsonObject["url"]!!.jsonPrimitive.content,
        )
        assertTrue(providers.containsKey("localGit"))
    }

    @Test
    fun `ai reports with object config preserves settings`() {
        val data = mapOf<String, Any?>(
            "ai" to mapOf(
                "provider" to "openai",
                "reports" to mapOf(
                    "smart-digest" to mapOf(
                        "audience" to "developer",
                        "max-length" to 500,
                    ),
                ),
            ),
        )
        val config = ConfigMapParser.parseFullConfig(data).config

        val reports = config.aiReports
        assertNotNull(reports)
        val digest = reports["smartDigest"]!!.jsonObject
        assertEquals("developer", digest["audience"]!!.jsonPrimitive.content)
    }
}
