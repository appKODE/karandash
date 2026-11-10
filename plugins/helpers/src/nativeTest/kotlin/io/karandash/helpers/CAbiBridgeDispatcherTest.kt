@file:OptIn(ExperimentalForeignApi::class)

package io.karandash.helpers

import io.karandash.api.PluginContext
import io.karandash.api.module.ReporterModule
import io.karandash.models.finding.Finding
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlinx.serialization.json.JsonElement
import platform.posix.pthread_self
import kotlin.test.Test
import kotlin.test.assertNotEquals

/** C ABI exports run plugin coroutines on the bridge pool, not on the host thread blocked in the call. */
class CAbiBridgeDispatcherTest {
    private class ThreadRecordingReporter : ReporterModule {
        override val id = "thread-recorder"
        override val name = id
        override val version = "1.0.0"
        override val formatId = id

        override suspend fun initialize(context: PluginContext) = true

        override suspend fun report(
            findings: List<Finding>,
            passed: Boolean,
            config: JsonElement,
        ): String = pthread_self().toString()
    }

    @Test
    fun reporterRunsOffTheCallingThread() {
        val output = memScoped {
            CAbiBridge.wrapReport(ThreadRecordingReporter(), "[]".cstr.ptr, 1, "{}".cstr.ptr)
        }
        val pluginThread = checkNotNull(output) { "wrapReport returned null" }
        try {
            assertNotEquals(pthread_self().toString(), pluginThread.toKString())
        } finally {
            CAbiBridge.freeString(pluginThread)
        }
    }
}
