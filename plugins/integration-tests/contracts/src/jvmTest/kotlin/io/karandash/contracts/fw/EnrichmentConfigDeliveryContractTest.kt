package io.karandash.contracts.fw

import io.karandash.contracts.support.FwProbeEnrichmentProvider
import io.karandash.contracts.support.createFwTempDir
import io.karandash.contracts.support.runFwPipeline
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Enrichment providers always get an empty config, and neither
 * `enrichment.<id>.enabled: false` nor `Module.defaultEnabled = false` stops them from running -
 * `executeEnrichmentProviders` passes `emptyConfig` and only checks policy overrides.
 */
class EnrichmentConfigDeliveryContractTest {
    @Test
    fun `enrichment provider receives its enrichment config block`() =
        runTest {
            val probe = FwProbeEnrichmentProvider()
            runFwPipeline(
                dir = createFwTempDir(),
                configYaml = """
                    enrichment:
                      probe:
                        url: https://jira.example.test
                """,
                extraModules = listOf(probe),
            )
            val seen = probe.initConfigs + probe.enrichConfigs
            val gotUrl = seen.any { ((it as? JsonObject)?.get("url") as? JsonPrimitive)?.content == "https://jira.example.test" }
            assertTrue(gotUrl, "provider never saw enrichment.probe.url; configs received: $seen")
        }

    @Test
    fun `enrichment provider disabled in config is never initialized`() =
        runTest {
            val probe = FwProbeEnrichmentProvider()
            runFwPipeline(
                dir = createFwTempDir(),
                configYaml = """
                    enrichment:
                      probe:
                        enabled: false
                """,
                extraModules = listOf(probe),
            )
            assertTrue(probe.initConfigs.isEmpty(), "enrichment.probe.enabled=false ignored; initialize() was called")
        }

    @Test
    fun `enrichment provider with defaultEnabled false and no config is never initialized`() =
        runTest {
            val probe = FwProbeEnrichmentProvider(defaultEnabled = false)
            runFwPipeline(
                dir = createFwTempDir(),
                configYaml = """
                    rules:
                      conflict: {}
                """,
                extraModules = listOf(probe),
            )
            assertTrue(probe.initConfigs.isEmpty(), "defaultEnabled=false ignored; unconfigured provider was initialized")
        }
}
