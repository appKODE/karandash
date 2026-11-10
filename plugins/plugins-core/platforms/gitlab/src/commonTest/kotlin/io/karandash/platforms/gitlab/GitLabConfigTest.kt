package io.karandash.platforms.gitlab

import io.karandash.api.support.mapToJsonElement
import io.karandash.models.support.KarandashJson
import io.karandash.sdk.config.configFieldsToProperties
import io.karandash.sdk.config.configSchemaFromDescriptor
import kotlin.test.Test
import kotlin.test.assertEquals

class GitLabConfigTest {
    @Test
    fun `default deleteInlineStrategy is discussion`() {
        val config = GitLabConfig()
        assertEquals(DeleteInlineStrategy.DISCUSSION, config.deleteInlineStrategy)
    }

    @Test
    fun `deleteInlineStrategy note is parsed from json`() {
        val json = mapToJsonElement(mapOf("deleteInlineStrategy" to "note"))
        val config = KarandashJson.Default.decodeFromJsonElement(GitLabConfig.serializer(), json)
        assertEquals(DeleteInlineStrategy.NOTE, config.deleteInlineStrategy)
    }

    @Test
    fun `deleteInlineStrategy discussion is parsed from json`() {
        val json = mapToJsonElement(mapOf("deleteInlineStrategy" to "discussion"))
        val config = KarandashJson.Default.decodeFromJsonElement(GitLabConfig.serializer(), json)
        assertEquals(DeleteInlineStrategy.DISCUSSION, config.deleteInlineStrategy)
    }

    @Test
    fun `deleteInlineStrategy defaults to discussion when absent from json`() {
        val json = mapToJsonElement(emptyMap<String, Any?>())
        val config = KarandashJson.Default.decodeFromJsonElement(GitLabConfig.serializer(), json)
        assertEquals(DeleteInlineStrategy.DISCUSSION, config.deleteInlineStrategy)
    }

    @Test
    fun `schema includes deleteInlineStrategy property`() {
        val schema = configFieldsToProperties(configSchemaFromDescriptor(GitLabConfig.serializer().descriptor))
        val property = schema.find { it.name == "deleteInlineStrategy" }
        assertEquals("deleteInlineStrategy", property?.name)
    }
}
