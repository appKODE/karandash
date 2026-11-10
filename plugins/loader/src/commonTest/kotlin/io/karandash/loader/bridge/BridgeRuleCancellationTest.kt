package io.karandash.loader.bridge

import io.karandash.loader.bridge.modules.BridgeRuleModule
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertFailsWith

class BridgeRuleCancellationTest {
    private class CancellingTransport : BridgeTransport {
        override suspend fun call(
            method: String,
            vararg args: String,
        ): String? = throw CancellationException("cancelled")

        override suspend fun callVoid(
            method: String,
            vararg args: String,
        ) {}

        override fun getProperty(name: String): String = ""

        override fun getIntProperty(
            name: String,
            default: Int,
        ): Int = default

        override fun getBoolProperty(
            name: String,
            default: Boolean,
        ): Boolean = default

        override fun hasCapability(name: String): Boolean = true

        override fun logError(
            method: String,
            exception: Exception,
        ) {}
    }

    @Test
    fun `a cancelled transport call propagates out of validate`() =
        runTest {
            val module = BridgeRuleModule(CancellingTransport())
            assertFailsWith<CancellationException> {
                module.validate("r", createTestMergeRequest(), JsonObject(emptyMap()))
            }
        }
}
