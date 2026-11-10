package io.karandash.cli.pipeline

import io.karandash.api.ModuleRegistryImpl
import io.karandash.api.PluginContext
import io.karandash.api.context.EnrichmentContext
import io.karandash.api.finding.ProvenanceStatus
import io.karandash.api.module.EnrichmentProviderModule
import io.karandash.bootstrap.CliLogger
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cli.GlobalWatchdog
import io.karandash.config.Config
import io.karandash.config.TimeoutConfig
import io.karandash.duration.KarandashDuration
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import io.karandash.sdk.NoOpLogger
import io.karandash.testfixtures.TestFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** `timeouts.enrichment` and `timeouts.global` set to non-default values and the run behaviour they change. */
class TimeoutsKeyBehaviourTest {
    private class SlowProvider : EnrichmentProviderModule {
        override val id = "slow"
        override val name = "slow"
        override val version = "1.0"
        override val dslName = "slow"
        override val providedEnrichment = setOf("slow:data")

        override suspend fun initialize(context: PluginContext): Boolean = true

        override suspend fun enrich(
            mr: MergeRequest,
            config: JsonElement,
            upstreamContext: EnrichmentContext,
        ): Map<String, JsonElement> {
            delay(5.seconds)
            return mapOf("slow:data" to JsonPrimitive("x"))
        }
    }

    private val mr = MergeRequest(
        id = "1",
        iid = 1,
        title = "t",
        description = null,
        author = User(username = "dev", name = "Dev"),
        sourceBranch = "feature",
        targetBranch = "main",
        state = MergeRequestState.OPENED,
        draft = false,
        labels = emptyList(),
        changes = emptyList(),
        commits = emptyList(),
    )

    private suspend fun slowStatus(enrichment: KarandashDuration): ProvenanceStatus {
        val registry = ModuleRegistryImpl()
        registry.register(SlowProvider())
        val stage = ConfigLoadStage(
            config = Config(timeouts = TimeoutConfig(enrichment = enrichment)),
            configFilePath = null,
            usingZeroConfig = false,
            registry = registry,
            bootstrap = PluginBootstrap(),
        )
        return executeEnrichmentProviders(stage, mr, logger = NoOpLogger).enrichers.single().status
    }

    @Test
    fun `timeouts enrichment 1 second fails a 5 second provider and 30 seconds lets it finish`() =
        runTest {
            assertEquals(ProvenanceStatus.FAILED, slowStatus(KarandashDuration.ofSeconds(1)))
            assertEquals(ProvenanceStatus.OK, slowStatus(KarandashDuration.ofSeconds(30)))
        }

    private suspend fun loadWithGlobal(yaml: String) {
        val dir = TestFiles.createTestTempDirectory("timeouts-global")
        TestFiles.writeText(Path(dir, "config.yaml"), yaml)
        val bootstrap = PluginBootstrap(envReader = { null })
        bootstrap.loadPluginsFromDirs(emptyList())
        try {
            val options = ValidationOptions(
                configPathStr = "$dir/config.yaml",
                mrDataStr = null,
                mrId = null,
                platform = null,
                project = null,
                prUrl = null,
                format = "json",
                outputPathStr = null,
                quiet = false,
                verbose = false,
                strict = false,
                dryRun = false,
                explain = false,
                fix = false,
                yes = false,
                noAutoDetect = true,
                outputWriter = {},
                sharedBootstrap = bootstrap,
            )
            ConfigLoader(options, bootstrap, CliLogger(sink = {})).loadConfig()
        } finally {
            bootstrap.shutdown()
        }
    }

    private suspend fun runUnder(
        config: String,
        watchdog: GlobalWatchdog,
    ): Int? =
        watchdog.run {
            loadWithGlobal(config)
            delay(5.seconds)
            1
        }

    // Real dispatcher on purpose: the config load does real IO and virtual time would skip past the watchdog.
    @Test
    fun `timeouts global 1 second in the config cuts a 5 second run under a 600 second watchdog`() =
        runTest(timeout = 3.minutes) {
            withContext(Dispatchers.Default) {
                val shorter = runUnder("timeouts:\n  global: 1s\n", GlobalWatchdog(600.seconds, fixedByCli = false))
                val unset = runUnder("version: 1\n", GlobalWatchdog(600.seconds, fixedByCli = false))

                assertNull(shorter)
                assertEquals(1, unset)
            }
        }
}
