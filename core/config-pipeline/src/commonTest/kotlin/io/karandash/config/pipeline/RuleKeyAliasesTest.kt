package io.karandash.config.pipeline

import io.karandash.api.support.mapToJsonElement
import io.karandash.logger.Logger
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RuleKeyAliasesTest {
    private class RecordingLogger : Logger {
        val warnings = mutableListOf<String>()
        val infos = mutableListOf<String>()

        override fun debug(message: String) = Unit

        override fun info(message: String) {
            infos += message
        }

        override fun warn(message: String) {
            warnings += message
        }

        override fun error(message: String) = Unit
    }

    @Test
    fun `boolean block keys produce one info note`() {
        val logger = RecordingLogger()
        val rule = buildJsonObject {
            put("blockOnDraft", true)
            putJsonArray("patterns") {
                addJsonObject { put("blockOnDraft", false) }
                add("unrelated")
            }
            put("blockDraft", "warn")
        }

        val paths = RuleKeyAliases.booleanBlockKeys("fileChanges", rule)
        RuleKeyAliases.noteBooleanBlockKeys(paths, logger)

        assertEquals(2, paths.size)
        assertEquals(1, logger.infos.size)
    }

    @Test
    fun `an unknown rule severity warns at parse while a valid one stays silent`() {
        val logger = RecordingLogger()
        val data = mapOf<String, Any?>(
            "rules" to mapOf(
                "title-format" to mapOf("severity" to "bogus"),
                "size-limits" to mapOf("severity" to "Warning"),
            ),
        )

        ConfigMapParser.parseFullConfig(mapToJsonElement(data), logger = logger)

        val warning = logger.warnings.single()
        assertTrue(warning.contains("rules.titleFormat.severity") && warning.contains("bogus"), warning)
    }
}
