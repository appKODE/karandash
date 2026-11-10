package io.karandash.loader.js

import com.dokar.quickjs.QuickJs
import io.karandash.loader.bridge.BridgeConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [io.karandash.loader.bridge.transport.JsTransport] quotes each argument kind and decodes each result kind. */
class JsTransportTest {
    private lateinit var engine: QuickJs

    @BeforeTest
    fun setup() {
        engine = QuickJs.create(Dispatchers.Default)
    }

    @AfterTest
    fun teardown() {
        engine.close()
    }

    private val source =
        """
        module.exports = {
            id: "js-t",
            name: "JS T",
            version: "1.0",
            echo: function() { return Array.prototype.map.call(arguments, function(a) { return typeof a + ':' + a; }).join('|'); },
            nothing: function() { return undefined; },
            boom: function() { throw new Error('bad'); },
            validate: function(ruleId, mr, cfg) { return typeof ruleId + ':' + ruleId + '|' + typeof mr + ':' + mr.k + '|' + typeof cfg; },
            setDraft: function(p, m, draft) { globalThis.lastDraft = typeof draft + ':' + draft; },
            draftSeen: function() { return globalThis.lastDraft; },
            pwnedSeen: function() { return globalThis.pwned; },
            short: 7,
            count: '12',
            flag: 'true',
            obj: { a: 1 },
            formatId: 'fmt'
        };
        """.trimIndent()

    private suspend fun transport() = JsModuleBridge.fromSource(engine, source, "t.js").transport

    @Test
    fun `arguments are string literals never code`() =
        runTest {
            val transport = transport()
            val injected = "{a:(globalThis.pwned='yes')}"
            val tricky = "it's\n\t\r\\x\" "
            val echoed = transport.call("echo", injected, "[1]", "true", "null", tricky)
            assertEquals("string:$injected|string:[1]|string:true|object:null|string:$tricky", echoed)
            assertNull(transport.call("pwnedSeen"), "an argument must not run as code")
        }

    @Test
    fun `json and int params per BridgeAbi are parsed in the shim`() =
        runTest {
            val transport = transport()
            val result = transport.call("validate", """{"r":1}""", """{"k":5}""", "{}")
            assertEquals("""string:{"r":1}|object:5|object""", result)
            transport.callVoid("setDraft", "p", "1", "true")
            assertEquals("boolean:true", transport.call("draftSeen"))
        }

    @Test
    fun `undefined results and throwing members answer null`() =
        runTest {
            val transport = transport()
            assertNull(transport.call("nothing"))
            assertNull(transport.call("missingMember"))
            assertNull(transport.call("boom"))
            transport.callVoid("boom")
            assertEquals("7", transport.call("short"))
            assertEquals("""{"a":1}""", transport.call("obj"))
            assertEquals("fmt", transport.call("formatId"))
        }

    @Test
    fun `properties fall back to defaults when absent or unparsable`() =
        runTest {
            val transport = transport()
            assertEquals("js-t", transport.getProperty(BridgeConstants.PROP_ID))
            assertEquals("", transport.getProperty("missingMember"))
            assertEquals("""{"a":1}""", transport.getProperty("obj"))
            assertEquals(12, transport.getIntProperty("count", 0))
            assertEquals(7, transport.getIntProperty("flag", 7))
            assertTrue(transport.getBoolProperty("flag", false))
            assertFalse(transport.getBoolProperty("count", false))
            assertTrue(transport.hasCapability("echo"))
            assertFalse(transport.hasCapability("missingMember"))
        }
}
