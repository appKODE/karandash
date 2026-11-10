@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.sdk.platform

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class ReinitializableClientTest {
    private class Client(
        val key: String,
    )

    @Test
    fun unchangedKeyKeepsTheClientAndChangedKeyClosesTheOldOneAfterTheSwap() =
        runTest {
            val events = mutableListOf<String>()
            val holders = mutableListOf<ReinitializableClient<Client>>()
            holders += ReinitializableClient { client ->
                // record which client was current when this one closed
                events += "close ${client.key} while current=${holders.single().value?.key}"
            }
            val holder = holders.single()

            val first = holder.rebuild("a") { Client("a") }
            assertSame(first, holder.rebuild("a") { error("must not rebuild for an unchanged key") })
            assertEquals(emptyList(), events)

            val second = holder.rebuild("b") { Client("b") }
            assertSame(second, holder.value)
            assertEquals(listOf("close a while current=b"), events, "the old client closes only after the swap")

            holder.close()
            holder.close()
            assertEquals(listOf("close a while current=b", "close b while current=null"), events)
            assertNull(holder.value)
        }
}
