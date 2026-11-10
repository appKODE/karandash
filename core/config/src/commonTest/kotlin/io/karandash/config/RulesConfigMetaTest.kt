package io.karandash.config

import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class RulesConfigMetaTest {
    @Test
    fun `metaFor returns default when ruleId not in ruleMeta`() {
        val config = RulesConfig(ruleConfigs = mapOf("myRule" to JsonObject(emptyMap())))
        val meta = config.metaFor("myRule")
        assertEquals(RuleExecutionMeta.DEFAULT_PRIORITY, meta.priority)
        assertEquals(false, meta.bailOnFail)
    }

    @Test
    fun `metaFor returns stored meta when present`() {
        val stored = RuleExecutionMeta(priority = 5, bailOnFail = true)
        val config = RulesConfig(
            ruleConfigs = mapOf("myRule" to JsonObject(emptyMap())),
            ruleMeta = mapOf("myRule" to stored)
        )
        assertEquals(stored, config.metaFor("myRule"))
    }

    @Test
    fun `metaFor tolerates naming convention of the lookup id`() {
        val stored = RuleExecutionMeta(priority = 7)
        val config = RulesConfig(
            ruleConfigs = mapOf("goTitleLength" to JsonObject(emptyMap())),
            ruleMeta = mapOf("goTitleLength" to stored),
        )
        assertEquals(stored, config.metaFor("go-title-length"))
        assertEquals(stored, config.metaFor("go_title_length"))
        assertEquals(stored, config.metaFor("GoTitleLength"))
    }
}
