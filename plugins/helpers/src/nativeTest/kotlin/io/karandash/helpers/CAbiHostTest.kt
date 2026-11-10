@file:OptIn(ExperimentalForeignApi::class)

package io.karandash.helpers

import io.karandash.api.PluginContext
import io.karandash.api.ai.AiAnalyzerContext
import io.karandash.api.ai.AiAnalyzerModule
import io.karandash.api.ai.AiCompletionRequest
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import io.karandash.models.support.KarandashJson
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import platform.posix.free
import platform.posix.strdup
import kotlin.test.Test
import kotlin.test.assertEquals

/** The K/N guest end of ABI v2: [CAbiHost] reaches the host through the `karandash_set_host` callbacks. */
class CAbiHostTest {
    private class EchoAnalyzer : AiAnalyzerModule {
        override val id = "echo"
        override val name = id
        override val version = "1.0.0"
        override val analyzerId = id

        override suspend fun initialize(context: PluginContext) = true

        override suspend fun analyze(context: AiAnalyzerContext): List<Finding> {
            val answer = context.aiService.complete(AiCompletionRequest(prompt = "p", model = context.model))
            val keys = (context.config as JsonObject).keys.sorted().joinToString(",")
            return listOf(Finding(Severity.INFO, "${answer.content} ${context.extraInstructions} keys=$keys", id))
        }
    }

    @Test
    fun analyzeCompletesThroughTheHostChannel() =
        runTest {
            assertEquals(0, CAbiHost.set(1, null, CALL, FREE))
            assertEquals(1, CAbiHost.set(CAbiHost.ABI, null, CALL, FREE))
            val mr =
                MergeRequest(
                    id = "1",
                    iid = 1,
                    title = "t",
                    description = "",
                    sourceBranch = "f",
                    targetBranch = "main",
                    author = User("a", "A", "a@example.com"),
                    state = MergeRequestState.OPENED,
                    draft = false,
                    labels = emptyList(),
                    commits = emptyList(),
                    changes = emptyList(),
                )
            val mrJson = KarandashJson.Default.encodeToString(MergeRequest.serializer(), mr)
            val config = """{"rule":1,"aiServiceAvailable":true,"instructions":"be brief","extraInstructions":"x"}"""
            val out =
                memScoped {
                    CAbiBridge.wrapAnalyze(EchoAnalyzer(), mrJson.cstr.ptr, """{"remaining":9}""".cstr.ptr, "m".cstr.ptr, config.cstr.ptr)
                }
            val findings = KarandashJson.Default.decodeFromString<List<Finding>>(out.toKString().also { CAbiBridge.freeString(out) })

            assertEquals(listOf("ok be brief keys=rule"), findings.map { it.message })
            assertEquals(
                "v",
                CAbiPluginContext.EMPTY.cacheProvider
                    .getStringCache("c")
                    .get("k")
            )
        }

    private companion object {
        val CALL =
            staticCFunction { _: COpaquePointer?, service: CPointer<ByteVar>?, _: CPointer<ByteVar>? ->
                val reply =
                    when (service?.toKString()) {
                        "ai.complete" -> """{"content":"ok","model":"m","usage":{"totalTokens":3}}"""
                        "cache.get" -> """{"value":"v"}"""
                        else -> "{}"
                    }
                strdup(reply)
            }
        val FREE = staticCFunction { ptr: CPointer<ByteVar>? -> free(ptr) }
    }
}
