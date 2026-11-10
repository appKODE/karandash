package io.karandash.scripts

import io.karandash.duration.KarandashDuration
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ScriptConfigParserTest {
    @Test
    fun parseSingleEntry() {
        val raw = listOf(JsonObject(mapOf("path" to JsonPrimitive(".karandash/checks.js"))))
        val entries = ScriptConfigParser.parse(raw)
        assertEquals(1, entries.size)
        assertEquals(".karandash/checks.js", entries[0].path)
        assertEquals(ScriptMode.AUTO, entries[0].mode)
        assertEquals(ScriptEntryConfig.DEFAULT_TIMEOUT, entries[0].timeout)
    }

    @Test
    fun parseMultipleEntries() {
        val raw = listOf(
            JsonObject(mapOf("path" to JsonPrimitive("a.js"))),
            JsonObject(mapOf("path" to JsonPrimitive("b.js"), "mode" to JsonPrimitive("subprocess"), "timeout" to JsonPrimitive(15000))),
        )
        val entries = ScriptConfigParser.parse(raw)
        assertEquals(2, entries.size)
        assertEquals("a.js", entries[0].path)
        assertEquals(ScriptMode.AUTO, entries[0].mode)
        assertEquals("b.js", entries[1].path)
        assertEquals(ScriptMode.SUBPROCESS, entries[1].mode)
        assertEquals(KarandashDuration.ofMillis(15000), entries[1].timeout)
    }

    @Test
    fun parseSandboxMode() {
        val raw = listOf(JsonObject(mapOf("path" to JsonPrimitive("x.js"), "mode" to JsonPrimitive("sandbox"))))
        val entries = ScriptConfigParser.parse(raw)
        assertEquals(ScriptMode.SANDBOX, entries[0].mode)
    }

    @Test
    fun parseAutoMode() {
        val raw = listOf(JsonObject(mapOf("path" to JsonPrimitive("x.js"), "mode" to JsonPrimitive("auto"))))
        val entries = ScriptConfigParser.parse(raw)
        assertEquals(ScriptMode.AUTO, entries[0].mode)
    }

    @Test
    fun parseNullModeDefaultsToAuto() {
        val raw = listOf(JsonObject(mapOf("path" to JsonPrimitive("x.js"), "mode" to JsonNull)))
        val entries = ScriptConfigParser.parse(raw)
        assertEquals(ScriptMode.AUTO, entries[0].mode)
    }

    @Test
    fun parseMissingPathThrows() {
        val raw = listOf(JsonObject(mapOf("mode" to JsonPrimitive("sandbox"))))
        assertFailsWith<IllegalArgumentException> {
            ScriptConfigParser.parse(raw)
        }
    }

    @Test
    fun parseUnknownModeThrows() {
        val raw = listOf(JsonObject(mapOf("path" to JsonPrimitive("x.js"), "mode" to JsonPrimitive("invalid"))))
        assertFailsWith<IllegalArgumentException> {
            ScriptConfigParser.parse(raw)
        }
    }

    @Test
    fun parseTimeoutFromNumber() {
        val raw = listOf(JsonObject(mapOf("path" to JsonPrimitive("x.js"), "timeout" to JsonPrimitive(5000))))
        val entries = ScriptConfigParser.parse(raw)
        assertEquals(KarandashDuration.ofMillis(5000), entries[0].timeout)
    }

    @Test
    fun parseTimeoutFromString() {
        val raw = listOf(JsonObject(mapOf("path" to JsonPrimitive("x.js"), "timeout" to JsonPrimitive("10000"))))
        val entries = ScriptConfigParser.parse(raw)
        assertEquals(KarandashDuration.ofMillis(10000), entries[0].timeout)
    }

    @Test
    fun parseEmptyList() {
        val entries = ScriptConfigParser.parse(emptyList())
        assertTrue(entries.isEmpty())
    }

    @Test
    fun parseModeIsCaseInsensitive() {
        val raw = listOf(JsonObject(mapOf("path" to JsonPrimitive("x.js"), "mode" to JsonPrimitive("SUBPROCESS"))))
        val entries = ScriptConfigParser.parse(raw)
        assertEquals(ScriptMode.SUBPROCESS, entries[0].mode)
    }

    @Test
    fun parseEnvAllowlist() {
        val env = JsonArray(listOf(JsonPrimitive("JIRA_TOKEN"), JsonPrimitive("CI_PROJECT_ID")))
        val raw = listOf(JsonObject(mapOf("path" to JsonPrimitive("x.js"), "env" to env)))
        assertEquals(listOf("JIRA_TOKEN", "CI_PROJECT_ID"), ScriptConfigParser.parse(raw)[0].env)
    }

    @Test
    fun parseEmptyEnvIsDeclaredEmpty() {
        val raw = listOf(JsonObject(mapOf("path" to JsonPrimitive("x.js"), "env" to JsonArray(emptyList()))))
        assertEquals(emptyList<String>(), ScriptConfigParser.parse(raw)[0].env)
    }

    @Test
    fun parseMissingOrNullEnvIsUndeclared() {
        val raw = listOf(
            JsonObject(mapOf("path" to JsonPrimitive("x.js"))),
            JsonObject(mapOf("path" to JsonPrimitive("y.js"), "env" to JsonNull)),
        )
        assertEquals(listOf(null, null), ScriptConfigParser.parse(raw).map { it.env })
    }

    @Test
    fun parseNonListEnvThrows() {
        val raw = listOf(JsonObject(mapOf("path" to JsonPrimitive("x.js"), "env" to JsonPrimitive("JIRA_TOKEN"))))
        assertFailsWith<io.karandash.api.exception.ConfigParseException> { ScriptConfigParser.parse(raw) }
    }
}
