package io.karandash.subprocess

import io.karandash.platform.PlatformProcess
import io.karandash.platform.createDirectories
import io.karandash.platform.readText
import io.karandash.platform.writeText
import io.karandash.testfixtures.TestFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** 1.12: a bridge JVM that never answers the discover handshake is killed at the timeout, not left running. */
class JarBridgeClientTimeoutTest {
    private val dir = TestFiles.createTestTempDirectory("jar-hang").also { createDirectories(it) }
    private val pidFile = Path(dir, "pid")

    private fun hangingJava(): String {
        val java = Path(dir, "java")
        // `exec` keeps the pid: the process the watchdog must kill is the one recorded here.
        java.writeText("#!/bin/sh\necho $$ > '$pidFile'\nexec sleep 300\n")
        check(PlatformProcess.execute(listOf("chmod", "+x", java.toString())).isSuccess)
        return java.toString()
    }

    @Test
    fun hungHandshakeFailsAndKillsTheSubprocess() =
        runTest(timeout = 60.seconds) {
            withContext(Dispatchers.Default) {
                val client = JarBridgeClient(hangingJava(), "unused.jar", timeoutSeconds = 2)

                val response = client.send(JarBridgeRequest(method = JarBridgeProtocol.METHOD_DISCOVER))

                assertFalse(response.success, "$response")
                val pid = pidFile.readText().trim()
                assertTrue(pid.isNotEmpty(), "fake java never started")
                assertFalse(PlatformProcess.execute(listOf("kill", "-0", pid)).isSuccess, "bridge subprocess $pid still alive")
            }
        }
}
