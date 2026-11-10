package io.karandash.cli

import com.github.ajalt.clikt.testing.test
import io.karandash.http.NetworkPolicy
import kotlin.test.Test
import kotlin.test.assertEquals

class SuspendCliktCommandTest {
    /** Records the policy value it starts with, then allows private hosts for the rest of its run. */
    private class AllowingCommand(
        val seen: MutableList<Boolean?>,
    ) : SuspendCliktCommand("allowing") {
        override suspend fun suspendRun() {
            seen += NetworkPolicy.current()?.allowPrivateNetwork
            NetworkPolicy.setAllowPrivateNetwork(true)
        }
    }

    @Test
    fun `each command runs under its own network policy`() {
        val seen = mutableListOf<Boolean?>()
        AllowingCommand(seen).test("")
        AllowingCommand(seen).test("")
        assertEquals(listOf<Boolean?>(false, false), seen, "a command's allow-private-network leaked into the next")
    }
}
