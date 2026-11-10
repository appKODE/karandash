package io.karandash.detect

import io.karandash.api.ModuleRegistryImpl
import io.karandash.api.module.ReporterModule
import io.karandash.api.platform.ProviderDetectionScore
import io.karandash.models.finding.Finding
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProviderAutoDetectorTest {
    private class FakeReporter(
        override val formatId: String,
        override val isDefaultCI: Boolean = false,
        override val isDefaultInteractive: Boolean = false,
        override val isDefaultReporter: Boolean = false,
    ) : ReporterModule {
        override val id = "reporter-$formatId"
        override val name = formatId
        override val version = "1.0"

        override suspend fun initialize(context: io.karandash.api.PluginContext): Boolean = true

        override suspend fun report(
            findings: List<Finding>,
            passed: Boolean,
            config: JsonElement,
        ): String? = null
    }

    @Test
    fun scoresAreSortedDescendingAndProvidersWithoutAScoreAreDropped() =
        runTest {
            val providers = listOf(
                FakePlatformService(id = "low", score = 0.2),
                FakePlatformService(id = "none"),
                FakePlatformService(id = "high", score = 0.8),
            )

            val scores = ProviderAutoDetector(providers, envProvider = { null }).detectAll()

            assertEquals(listOf("high", "low"), scores.map { it.providerId })
        }

    @Test
    fun bestPickNeedsTheMinimumViableScore() =
        runTest {
            val min = ProviderDetectionScore.MIN_VIABLE_SCORE
            val atMin = ProviderAutoDetector(listOf(FakePlatformService(id = "edge", score = min)), envProvider = { null })
            val below = ProviderAutoDetector(listOf(FakePlatformService(id = "weak", score = min / 2)), envProvider = { null })

            assertEquals("edge", atMin.detectBest()?.providerId)
            assertNull(below.detectBest())
        }

    @Test
    fun smartDefaultsPickTheCiReporterInCiAndTheInteractiveOneOtherwise() =
        runTest {
            val registry = ModuleRegistryImpl()
            registry.register(FakeReporter("console", isDefaultInteractive = true))
            registry.register(FakeReporter("gitlab-comment", isDefaultCI = true))
            registry.register(FakeReporter("json", isDefaultReporter = true))
            val defaults = SmartReporterDefaults(registry)
            val ci = ProviderDetectionScore(providerId = "gitlab", score = 0.9, source = "ci:gitlab")

            assertEquals("gitlab-comment", defaults.resolveDefaultFormat(ci))
            assertEquals("console", defaults.resolveDefaultFormat(null))
            val diagnostics = defaults.diagnostics(ci)
            assertEquals(true, diagnostics.isCI)
            assertEquals(setOf("console", "gitlab-comment", "json"), diagnostics.availableFormats.toSet())
            assertEquals("json", diagnostics.fallbackDefault)
        }

    @Test
    fun smartDefaultsFallBackToTheDefaultReporterAndAreNullWithoutReporters() =
        runTest {
            val registry = ModuleRegistryImpl()
            assertNull(SmartReporterDefaults(registry).resolveDefaultFormat(null))

            registry.register(FakeReporter("json", isDefaultReporter = true))
            registry.register(FakeReporter("plain"))

            assertEquals("json", SmartReporterDefaults(registry).resolveDefaultFormat(null))
            assertEquals(
                "json",
                SmartReporterDefaults(registry).resolveDefaultFormat(ProviderDetectionScore(providerId = "x", score = 1.0, source = "s")),
            )
        }
}
