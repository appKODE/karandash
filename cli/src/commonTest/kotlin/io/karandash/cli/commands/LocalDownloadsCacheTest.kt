package io.karandash.cli.commands

import io.karandash.bootstrap.DelegatingLogger
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cache.DownloadsCacheConfig
import io.karandash.cli.TestFileHelpers
import io.karandash.duration.KarandashDuration
import io.karandash.http.NetworkPolicy
import io.karandash.logger.Logger
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `cache.downloads` for `plugin install/update`, read through the same loader as every other command. */
class LocalDownloadsCacheTest {
    private class WarnCapturingLogger : Logger {
        val warnings = mutableListOf<String>()

        override fun debug(message: String) = Unit

        override fun info(message: String) = Unit

        override fun warn(message: String) {
            warnings += message
        }

        override fun error(message: String) = Unit
    }

    /** Runs [localDownloadsCache] on [rootYaml] (beside a `base.yaml` holding [baseYaml]). */
    private suspend fun downloads(
        rootYaml: String,
        baseYaml: String = "dry-run: true",
        logger: Logger = WarnCapturingLogger(),
    ): DownloadsCacheConfig {
        val dir = TestFileHelpers.createTempDir("local-downloads-cache")
        try {
            TestFileHelpers.writeFile(Path(dir, "base.yaml"), baseYaml)
            val root = Path(dir, "config.yaml")
            TestFileHelpers.writeFile(root, rootYaml)
            val bootstrap = PluginBootstrap(
                envReader = { null },
                logger = DelegatingLogger(logger)
            ).also { it.loadPluginsFromDirs(emptyList()) }
            return localDownloadsCache(bootstrap, root.toString())
        } finally {
            TestFileHelpers.deleteTempDir(dir)
        }
    }

    @Test
    fun `a root without extends gives its own value`() =
        runTest {
            assertEquals(DownloadsCacheConfig(enabled = false), downloads("cache:\n  downloads:\n    enabled: false"))
            assertEquals(DownloadsCacheConfig(), downloads("dry-run: true"))
        }

    @Test
    fun `a parent value is inherited and the root own value wins`() =
        runTest {
            val parent = "cache:\n  downloads:\n    enabled: false\n    ttl: 5m"
            assertEquals(DownloadsCacheConfig(false, KarandashDuration.ofSeconds(300L)), downloads("extends: base.yaml", parent))
            assertEquals(
                DownloadsCacheConfig(true, KarandashDuration.ofSeconds(300L)),
                downloads("extends: base.yaml\ncache:\n  downloads:\n    enabled: true", parent),
            )
        }

    @Test
    fun `a parent env block interpolates the value`() =
        runTest {
            val parent = "env:\n  DL_TTL: 7m\ncache:\n  downloads:\n    ttl: \"${'$'}{DL_TTL}\""
            assertEquals(DownloadsCacheConfig(ttl = KarandashDuration.ofSeconds(420L)), downloads("extends: base.yaml", parent))
        }

    @Test
    fun `a missing plugin under on-missing fail still gives the configured value`() =
        runTest {
            val root = "plugins:\n  on-missing: fail\nrules:\n  no-such-module-xyz: {}\ncache:\n  downloads:\n    enabled: false"
            assertEquals(DownloadsCacheConfig(enabled = false), downloads(root))
        }

    @Test
    fun `unknown config keys do not warn when only cache downloads is read`() =
        runTest {
            val logger = WarnCapturingLogger()
            val root = "bogus-top-level: 1\ncache:\n  downloads:\n    enabled: false\n    bogus-nested: 2"

            assertEquals(DownloadsCacheConfig(enabled = false), downloads(root, logger = logger))
            assertTrue(logger.warnings.none { "bogus" in it }, "${logger.warnings}")
        }

    @Test
    fun `an unreachable parent falls back to defaults and leaves the policy of the caller alone`() =
        runTest {
            val logger = WarnCapturingLogger()
            val root = "extends: https://127.0.0.1:1/base.yaml\nallow-private-network: true\ncache:\n  downloads:\n    enabled: false"
            val policy = NetworkPolicy()

            assertEquals(DownloadsCacheConfig(), withContext(policy) { downloads(root, logger = logger) })
            assertTrue(logger.warnings.any { it.contains("Ignoring local config for 'cache.downloads'") }, "${logger.warnings}")
            assertFalse(policy.allowPrivateNetwork, "the value the root set is not left behind")
        }
}
