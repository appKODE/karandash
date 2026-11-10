package io.karandash.loader.jar

import com.dokar.quickjs.QuickJs
import io.karandash.api.PluginContext
import io.karandash.loader.bridge.BridgeContextSerializer
import io.karandash.loader.js.JsContextInjector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `PluginContext.resolvePattern` across the plugin bridges: a bridged guest sees the patterns the host resolves.
 *
 */
class BridgePatternResolutionTest {
    /** Host context whose resolver knows a user-defined `@ticket` pattern (the SDK default would match it literally). */
    private val host =
        object : PluginContext {
            override val pluginConfig: JsonElement = JsonObject(emptyMap())
            override val hostVersion: String = "1.0.0"
            override val workingDirectory: String = "/test"

            override fun log(message: String) = Unit

            override fun warn(message: String) = Unit

            override fun env(name: String): String? = null

            override fun resolvePattern(pattern: String): Regex =
                if (pattern == TICKET) Regex(TICKET_REGEX) else super.resolvePattern(pattern)

            override fun namedPatternNames(): List<String> = listOf(TICKET)
        }

    /** Host whose named-pattern table is far larger than the bridge cap; every entry resolves to `x`. */
    private val crowdedHost =
        object : PluginContext by host {
            override fun resolvePattern(pattern: String): Regex = if (pattern == TICKET) host.resolvePattern(pattern) else Regex("x")

            override fun namedPatternNames(): List<String> = List(BridgeContextSerializer.MAX_PATTERNS * 2) { "@p$it" }
        }

    @Test
    fun `a user pattern the plugin config never mentions reaches the bridge context`() {
        val config = Json.parseToJsonElement("""{"labels":["bug"]}""")
        val guest = JarBridgePluginContext.fromJson(BridgeContextSerializer.serialize(host, config))

        assertTrue(guest.resolvePattern(TICKET).matches("ABC-12"))
    }

    @Test
    fun `the bridged pattern table is capped and keeps the patterns the config references`() {
        val config = Json.parseToJsonElement("""{"title":{"pattern":"@ticket"}}""")
        val json = BridgeContextSerializer.serialize(crowdedHost, config)
        val patterns = Json.parseToJsonElement(json).let { (it as JsonObject)[BridgeContextSerializer.PATTERNS] as JsonObject }

        assertEquals(BridgeContextSerializer.MAX_PATTERNS, patterns.size)
        assertTrue(JarBridgePluginContext.fromJson(json).resolvePattern(TICKET).matches("ABC-12"))
    }

    @Test
    fun `config patterns resolved by the host reach the JAR bridge context`() {
        val config = Json.parseToJsonElement("""{"title":{"pattern":"@ticket"},"labels":["bug"]}""")
        val guest = JarBridgePluginContext.fromJson(BridgeContextSerializer.serialize(host, config))

        assertTrue(guest.resolvePattern(TICKET).matches("ABC-12"))
    }

    @Test
    fun `the JAR bridge context compiles built-in and regex patterns instead of matching them literally`() {
        val guest = JarBridgePluginContext.fromJson("""{"pluginConfig":{}}""")

        assertTrue(guest.resolvePattern("regex:^v[0-9]+$").matches("v12"))
        assertTrue(guest.resolvePattern("@jira-ticket").containsMatchIn("fixes MAPS-42"))
        assertTrue(guest.resolvePattern("@unknown").matches("@unknown"))
    }

    @Test
    fun `JS guests resolve patterns through the host`() =
        runTest {
            val engine = QuickJs.create(Dispatchers.Default)
            try {
                JsContextInjector(engine, host).inject()
                assertEquals(TICKET_REGEX, engine.evaluate<String>("__karandash_resolve_pattern('$TICKET')"))
            } finally {
                engine.close()
            }
        }

    private companion object {
        const val TICKET = "@ticket"
        const val TICKET_REGEX = "[A-Z]+-[0-9]+"
    }
}
