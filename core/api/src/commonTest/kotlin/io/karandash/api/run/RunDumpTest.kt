@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.api.run

import io.karandash.api.ModuleRegistryImpl
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RunDumpTest {
    private class Unserializable

    @Test
    fun `dump helpers write inside a dumping run and never evaluate content elsewhere`() =
        runTest {
            val written = MutableStateFlow(emptyMap<String, String>())
            val evaluated = MutableStateFlow(0)
            val count = { evaluated.update { it + 1 } }

            dumpRun("outside.txt") { count().let { "x" } }
            val session = ModuleRegistryImpl().forRun()
            withContext(session.services) {
                dumpJson("no-dump.json") { count().let { 1 } }
                session.provide(RunServiceKeys.DUMP, RunDump { name, content -> written.update { it + (name to content) } })
                dumpRun("a.txt") { "text" }
                dumpJson("b.json") { listOf(1, 2) }
                dumpJson("c.json") { Unserializable() }
            }

            assertEquals(0, evaluated.value)
            assertEquals("text", written.value["a.txt"])
            assertEquals("[\n    1,\n    2\n]", written.value["b.json"])
            assertTrue(written.value.getValue("c.json").startsWith("dump of c.json failed: "), written.value["c.json"])
        }
}
