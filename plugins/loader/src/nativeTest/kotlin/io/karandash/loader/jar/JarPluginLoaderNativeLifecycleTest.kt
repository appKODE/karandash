@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.karandash.loader.jar

import io.karandash.api.ModuleRegistryImpl
import io.karandash.api.module.RuleModule
import io.karandash.api.module.ScriptModule
import io.karandash.crypto.KeyManager
import io.karandash.loader.PluginCache
import io.karandash.loader.RecordingLogger
import io.karandash.loader.RecordingPluginContext
import io.karandash.platform.PlatformEnvironment
import io.karandash.platform.PlatformProcess
import io.karandash.platform.createDirectories
import io.karandash.platform.readText
import io.karandash.platform.writeBytes
import io.karandash.platform.writeText
import io.karandash.testfixtures.TestFiles
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.serialization.json.JsonObject
import platform.posix.setenv
import platform.posix.unsetenv
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Lifecycle invariants of the native [JarPluginLoader] (JVM subprocess bridge). `JAVA_HOME` points at a fake
 * `java` script that speaks the bridge protocol, so the test needs no JDK and records every bridge call.
 */
class JarPluginLoaderNativeLifecycleTest {
    private val dir = TestFiles.createTestTempDirectory("jar-lifecycle").also { createDirectories(it) }
    private val log = Path(dir, "calls.log").also { it.writeText("") }
    private val savedEnv = ENV_VARS.associateWith { PlatformEnvironment.getEnv(it) }
    private val registry = ModuleRegistryImpl()

    @AfterTest
    fun restoreEnv() =
        savedEnv.forEach { (name, value) ->
            if (value == null) unsetenv(name) else setenv(name, value, 1)
        }

    private fun fakeJvm(modules: String = RULE_MODULE): Path {
        val bin = Path(dir, "jdk/bin").also { createDirectories(it) }
        val java = Path(bin, "java")
        java.writeText(
            """
            #!/bin/sh
            if [ "${'$'}1" = "-version" ]; then echo 'openjdk version "17"' >&2; exit 0; fi
            req=${'$'}(cat)
            ctx=''; case "${'$'}req" in *'"context":'*) ctx='+context' ;; esac
            case "${'$'}req" in
              *'"method":"discover"'*) echo discover >> '$log'
                echo '{"success":true,"modules":[$modules]}' ;;
              *'"method":"initialize"'*) echo "initialize${'$'}ctx" >> '$log'; echo '{"success":true}' ;;
              *'"op":"validate"'*) echo "validate${'$'}ctx" >> '$log'; echo '{"success":true,"data":{"result":"[]"}}' ;;
              *'"method":"shutdown"'*) echo shutdown >> '$log'; echo '{"success":true}' ;;
              *'"op":"execute"'*) echo "executeScripts${'$'}ctx" >> '$log'
                echo '{"success":true,"data":{"result":"[{\"rule\":\"jar-script\",\"message\":\"from jar\",\"severity\":\"WARNING\"}]"}}' ;;
              *) echo other >> '$log'; echo '{"success":true}' ;;
            esac
            """.trimIndent() + "\n",
        )
        check(PlatformProcess.execute(listOf("chmod", "+x", java.toString())).isSuccess)
        Path(dir, "bridge.jar").writeText("fake bridge")
        setenv("JAVA_HOME", Path(dir, "jdk").toString(), 1)
        setenv("KARANDASH_BRIDGE_JAR", Path(dir, "bridge.jar").toString(), 1)
        return Path(dir, "plugins").also {
            createDirectories(it)
            Path(it, "rule.jar").writeText("fake plugin")
        }
    }

    private fun calls() = log.readText().lines().filter { it.isNotBlank() }

    @Test
    fun discoveredRuleIsRegisteredValidatedAndShutDownOnce() =
        // Real time: the lifecycle's shutdown timeout would otherwise expire in virtual time while the subprocess runs.
        runTest(timeout = 60.seconds) {
            withContext(Dispatchers.Default) {
                val plugins = fakeJvm()
                val loader = JarPluginLoader(registry, PluginCache(), RecordingLogger())

                val result = loader.loadFromDirectories(listOf(plugins), RecordingPluginContext())
                assertEquals(listOf("jar-rule"), result.loaded, "$result")
                val rule = registry.get("jar-rule") as RuleModule
                assertEquals(emptyList(), rule.validate("jar-rule", createTestMergeRequest(), JsonObject(emptyMap())))

                assertTrue(loader.unload("jar-rule"))
                assertNull(registry.get("jar-rule"))
                assertFalse(loader.unload("jar-rule"), "second unload of the same module is a no-op")
                loader.unloadAll()
                loader.unloadAll()
                assertEquals(listOf("discover", "validate", "shutdown"), calls())
            }
        }

    @Test
    fun initializeContextTravelsWithLaterCallsAndContributedRulesComeFromDiscovery() =
        // Real time: each bridge call is a subprocess.
        runTest(timeout = 60.seconds) {
            withContext(Dispatchers.Default) {
                val plugins = fakeJvm()
                val loader = JarPluginLoader(registry, PluginCache(), RecordingLogger())
                loader.loadFromDirectories(listOf(plugins), RecordingPluginContext())
                val rule = registry.get("jar-rule") as RuleModule

                assertEquals(setOf("jarRule"), rule.contributedRules())
                assertTrue(rule.initialize(RecordingPluginContext()))
                rule.validate("jarRule", createTestMergeRequest(), JsonObject(emptyMap()))

                assertEquals(listOf("discover", "initialize+context", "validate+context"), calls())
            }
        }

    @Test
    fun unloadAllShutsDownTrackedModulesOnce() =
        // Real time: the lifecycle's shutdown timeout would otherwise expire in virtual time while the subprocess runs.
        runTest(timeout = 60.seconds) {
            withContext(Dispatchers.Default) {
                val plugins = fakeJvm()
                val loader = JarPluginLoader(registry, PluginCache(), RecordingLogger())

                loader.loadFromDirectories(listOf(plugins), RecordingPluginContext())
                loader.unloadAll()
                loader.unloadAll()
                assertNull(registry.get("jar-rule"))
                assertEquals(listOf("discover", "shutdown"), calls())
            }
        }

    @Test
    fun nonRuleModuleIsServedThroughTheTransportBridge() =
        // Real time: each bridge call is a subprocess.
        runTest(timeout = 60.seconds) {
            withContext(Dispatchers.Default) {
                val plugins = fakeJvm("""{"id":"jar-script","name":"Jar Script","version":"1.0.0","type":"SCRIPT","exports":["execute"]}""")
                val loader = JarPluginLoader(registry, PluginCache(), RecordingLogger())
                assertEquals(listOf("jar-script"), loader.loadFromDirectories(listOf(plugins), RecordingPluginContext()).loaded)

                val script = assertIs<ScriptModule>(registry.get("jar-script"))
                val findings = script.execute(emptyList(), createTestMergeRequest(), RecordingPluginContext())

                assertEquals(listOf("from jar"), findings.map { it.message })
                assertEquals(listOf("discover", "executeScripts+context"), calls())
            }
        }

    @Test
    fun unsignedJarIsRejectedBeforeTheBridgeWhenAKeyIsTrusted() =
        // Real time: each bridge call is a subprocess.
        runTest(timeout = 60.seconds) {
            withContext(Dispatchers.Default) {
                val plugins = fakeJvm()
                val keys = KeyManager(Path(dir, "keys"))
                val keyFile = Path(dir, "trusted.pub").also { it.writeBytes(RSA_KEY_DER) }
                assertTrue(keys.importKey(keyFile).isSuccess)
                val loader = JarPluginLoader(registry, PluginCache(), RecordingLogger()).apply { keyManager = keys }

                val result = loader.loadFromDirectories(listOf(plugins), RecordingPluginContext())

                assertEquals(emptyList(), result.loaded, "$result")
                assertTrue(
                    result.failed
                        .single()
                        .reason
                        .startsWith("Signature verification failed"),
                    "$result"
                )
                assertNull(registry.get("jar-rule"))
                assertEquals(emptyList(), calls(), "an untrusted JAR must not reach the bridge")
            }
        }

    private companion object {
        const val RULE_MODULE =
            """{"id":"jar-rule","name":"Jar Rule","version":"1.0.0","type":"RULE",""" +
                """"exports":["contributedRules","validate"],"abi":{"contributedRules":"[\"jarRule\"]"}}"""
        val ENV_VARS = listOf("JAVA_HOME", "KARANDASH_BRIDGE_JAR")

        /** SEQUENCE + rsaEncryption OID header, enough for [KeyManager.importKey]; no signature is ever checked. */
        val RSA_KEY_DER =
            byteArrayOf(
                0x30,
                0x2a,
                0x30,
                0x0d,
                0x06,
                0x09,
                0x2a,
                0x86.toByte(),
                0x48,
                0x86.toByte(),
                0xf7.toByte(),
                0x0d,
                0x01,
                0x01,
                0x01
            ) +
                ByteArray(32) { 1 }
    }
}
