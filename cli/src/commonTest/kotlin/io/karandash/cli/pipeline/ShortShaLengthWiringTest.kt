package io.karandash.cli.pipeline

import io.karandash.api.PluginContext
import io.karandash.api.module.RuleModule
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.logger.NoOpLogger
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import io.karandash.utils.mr.SHORT_SHA_LENGTH
import io.karandash.utils.mr.ShortSha
import io.karandash.utils.mr.shortSha
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import kotlinx.serialization.json.JsonElement
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes

/** `display.git.shortShaLength` sets the abbreviated SHA that rules put into finding text. */
class ShortShaLengthWiringTest {
    private class ShaRule : RuleModule {
        override val id = "short-sha-rule-module"
        override val name = id
        override val version = "1.0"

        override suspend fun contributedRules(): Set<String> = setOf(RULE)

        override suspend fun initialize(context: PluginContext): Boolean = true

        override suspend fun validate(
            ruleId: String,
            mr: MergeRequest,
            configElement: JsonElement,
        ): List<Finding> = listOf(Finding(Severity.WARNING, SHA.shortSha(), RULE))
    }

    @AfterTest
    fun reset() = ShortSha.configure(SHORT_SHA_LENGTH)

    private suspend fun findingMessage(display: String): String {
        val config = Path("cli/build/tmp/short-sha.yaml")
        SystemFileSystem.createDirectories(checkNotNull(config.parent))
        SystemFileSystem.sink(config).buffered().use {
            it.writeString(
                "cache:\n  enabled: false\nai:\n  enabled: false\n  statistics:\n    dir: cli/build/tmp/stats\nrules:\n  $RULE: {}\n$display"
            )
        }
        val bootstrap = PluginBootstrap(envReader = { null })
        bootstrap.loadPluginsFromDirs(emptyList())
        bootstrap.registry().register(ShaRule())
        val options = ValidationOptions(
            configPathStr = config.toString(),
            mrDataStr = MR,
            mrId = null,
            platform = null,
            project = null,
            prUrl = null,
            format = "json",
            explicitFormat = true,
            outputPathStr = null,
            quiet = true,
            verbose = false,
            strict = false,
            dryRun = false,
            explain = false,
            fix = false,
            yes = false,
            noAutoDetect = true,
            outputWriter = { },
            sharedBootstrap = bootstrap,
            logger = NoOpLogger,
        )
        try {
            val result = ValidationPipeline(options, bootstrap).execute()
            return checkNotNull(result.validationResult).findings.single { it.rule == RULE }.message
        } finally {
            bootstrap.shutdown()
        }
    }

    @Test
    fun `configured length abbreviates the SHA in finding text`() =
        runTest(timeout = 5.minutes) {
            assertEquals(SHA.take(12), findingMessage("display:\n  git:\n    shortShaLength: 12\n"))
        }

    @Test
    fun `unset length keeps seven characters even after a run that changed it`() =
        runTest(timeout = 5.minutes) {
            findingMessage("display:\n  git:\n    shortShaLength: 12\n")
            assertEquals(SHA.take(SHORT_SHA_LENGTH), findingMessage(""))
        }

    private companion object {
        const val MR = "tools/integration-tests/fixtures/test-mr.json"
        const val RULE = "short-sha-rule"
        const val SHA = "0123456789abcdef0123456789abcdef01234567"
    }
}
