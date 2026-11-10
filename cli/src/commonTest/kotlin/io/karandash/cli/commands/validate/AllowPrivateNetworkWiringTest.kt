package io.karandash.cli.commands.validate

import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cli.TestFileHelpers
import io.karandash.cli.pipeline.ConfigLoader
import io.karandash.http.NetworkPolicy
import io.karandash.logger.NoOpLogger
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `allow-private-network` (any spelling) of the merged config reaches the run's [NetworkPolicy]: a parent's counts like the root's. */
class AllowPrivateNetworkWiringTest {
    /** Loads [rootYaml] (beside a `base.yaml` holding [baseYaml]) through [ConfigLoader] under [policy]; returns its value afterwards. */
    private suspend fun load(
        rootYaml: String,
        baseYaml: String = "dry-run: true",
        policy: NetworkPolicy = NetworkPolicy(),
    ): Boolean {
        val dir = TestFileHelpers.createTempDir("allow-private-network")
        try {
            TestFileHelpers.writeFile(Path(dir, "base.yaml"), baseYaml)
            val root = Path(dir, "config.yaml")
            TestFileHelpers.writeFile(root, rootYaml)
            val bootstrap = PluginBootstrap(envReader = { null }).also { it.loadPluginsFromDirs(emptyList()) }
            try {
                withContext(policy) { ConfigLoader.forCommand(root.toString(), bootstrap, NoOpLogger).loadConfig() }
            } finally {
                bootstrap.shutdown()
            }
            return policy.allowPrivateNetwork
        } finally {
            TestFileHelpers.deleteTempDir(dir)
        }
    }

    @Test
    fun `kebab and snake case keys enable the policy`() =
        runTest {
            for (key in listOf("allow-private-network", "allow_private_network")) {
                val policy = NetworkPolicy()
                withContext(policy) { ValidateConfigPipeline.applyAllowPrivateNetwork(JsonObject(mapOf(key to JsonPrimitive(true)))) }
                assertTrue(policy.allowPrivateNetwork, key)
            }
        }

    @Test
    fun `absent or false key turns it off`() =
        runTest {
            val policy = NetworkPolicy(allowPrivateNetwork = true)
            withContext(policy) { ValidateConfigPipeline.applyAllowPrivateNetwork(JsonObject(emptyMap())) }
            assertFalse(policy.allowPrivateNetwork)
            policy.allowPrivateNetwork = true
            withContext(policy) {
                ValidateConfigPipeline.applyAllowPrivateNetwork(JsonObject(mapOf("allow_private_network" to JsonPrimitive(false))))
            }
            assertFalse(policy.allowPrivateNetwork)
        }

    @Test
    fun `root config enables it for the run`() = runTest { assertTrue(load("allow-private-network: true\nextends: base.yaml")) }

    @Test
    fun `an extends parent enables it and a root false overrides it`() =
        runTest {
            assertTrue(load("extends: base.yaml", baseYaml = "allow-private-network: true"))
            assertFalse(load("extends: base.yaml\nallow-private-network: false", baseYaml = "allow-private-network: true"))
        }

    @Test
    fun `an interpolated value counts for the run`() =
        runTest {
            assertTrue(load("env:\n  KD_TEST_APN: \"true\"\nallow-private-network: \"\${KD_TEST_APN}\""))
            assertTrue(load("extends: base.yaml", baseYaml = "env:\n  KD_TEST_APN: \"true\"\nallow-private-network: \"\${KD_TEST_APN}\""))
        }

    @Test
    fun `a later load without the flag turns it off`() =
        runTest {
            val policy = NetworkPolicy()
            assertTrue(load("allow-private-network: true", policy = policy))
            assertFalse(load("dry-run: true", policy = policy))
        }
}
