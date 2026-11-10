package io.karandash.loader.bridge

import io.karandash.api.facet.PluginCommandContext
import io.karandash.api.facet.PluginCommandSpec
import io.karandash.loader.BridgeDeserializer
import io.karandash.loader.RecordingLogger
import io.karandash.loader.RecordingPluginContext
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The `runCliCommand` run path every bridge gives the commands a guest lists. */
class BridgeCliCommandsTest {
    private class FakeTransport(
        private val result: String?,
        private val exported: Boolean = true,
    ) : AbiTableTransport("fake", RecordingLogger(), "Test") {
        val calls = mutableListOf<List<String>>()

        override fun exports(op: BridgeOp): Boolean = exported

        override fun invoke(
            op: BridgeOp,
            args: Array<out String>,
        ): String? {
            calls += listOf(op.op) + args
            return result
        }
    }

    private val specs =
        listOf(PluginCommandSpec(name = "tool", help = "group", subcommands = listOf(PluginCommandSpec(name = "greet", help = "leaf"))))

    private suspend fun runLeaf(transport: BridgeTransport): Triple<Int, List<String>, List<String>> {
        val out = mutableListOf<String>()
        val err = mutableListOf<String>()
        val leaf = BridgeCliCommands
            .runnable(specs, transport)
            .single()
            .subcommands
            .single()
        val code = checkNotNull(leaf.run)(PluginCommandContext(listOf("a", "b"), RecordingPluginContext(), { out += it }, { err += it }))
        return Triple(code, out, err)
    }

    @Test
    fun `a leaf runs through the op with its full path and args`() =
        runTest {
            val transport = FakeTransport("""{"exitCode":2,"stdout":["hi"],"stderr":["careful"]}""")

            assertEquals(Triple(2, listOf("hi"), listOf("careful")), runLeaf(transport))
            assertEquals(listOf(listOf("runCliCommand", "tool greet", """["a","b"]""")), transport.calls)
        }

    @Test
    fun `no result fails the command with exit code 1`() =
        runTest {
            val (code, out, err) = runLeaf(FakeTransport(null))

            assertEquals(1, code)
            assertEquals(emptyList(), out)
            assertEquals(1, err.size)
        }

    @Test
    fun `without the op the commands stay listed but not runnable`() {
        val listed = BridgeCliCommands.runnable(specs, FakeTransport(null, exported = false))

        assertEquals(specs, listed)
        assertNull(
            listed
                .single()
                .subcommands
                .single()
                .run
        )
    }

    @Test
    fun `listed subcommands are parsed recursively`() {
        val parsed = BridgeDeserializer.parsePluginCommandSpecs(
            """[{"name":"tool","help":"group","subcommands":[{"name":"greet","help":"leaf","subcommands":[]}]}]""",
        )

        assertEquals(specs, parsed)
    }
}
