package io.karandash.config.pipeline

import io.karandash.api.ExperimentalKarandashApi
import io.karandash.api.InternalKarandashApi
import io.karandash.api.ModuleRegistryImpl
import io.karandash.api.config.ConfigProperty
import io.karandash.api.config.ConfigPropertyType
import io.karandash.api.module.ConfigFormatModule
import io.karandash.api.module.SchemaAwareFormatModule
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.sdk.config.ConfigFormatModuleAdapter
import io.karandash.sdk.module.AbstractRuleModule
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

@OptIn(ExperimentalKarandashApi::class, InternalKarandashApi::class)
class ConfigFormatRegistryRuleSchemasTest {
    private class SchemaRule : AbstractRuleModule() {
        override val id = "schema-rule"
        override val name = "Schema Rule"
        override val version = "1.0"

        override suspend fun contributedRules() = setOf("myRule")

        override suspend fun configSchema() =
            listOf(
                ConfigProperty("pattern", ConfigPropertyType.STRING, "", required = true),
                ConfigProperty("max", ConfigPropertyType.INT, "", defaultValue = 72),
                ConfigProperty("labels", ConfigPropertyType.STRING_LIST, ""),
            )

        override suspend fun validate(
            ruleId: String,
            mr: MergeRequest,
            configElement: JsonElement,
        ): List<Finding> = emptyList()
    }

    /** Adopts per-call schemas: returns a configured copy, never touches its own state. */
    private class JsonSchemaFormat(
        val schemas: JsonObject? = null,
    ) : SchemaAwareFormatModule {
        override val formatId = "json-schema-format"
        override val fileExtensions = setOf("jsf")

        override fun withRuleSchemas(schemas: JsonObject): ConfigFormatModule = JsonSchemaFormat(schemas)

        override suspend fun parseToJsonElement(content: String): JsonElement = JsonObject(emptyMap())

        override suspend fun parseExtendsOnly(content: String): JsonElement? = null
    }

    /** Keeps the default withRuleSchemas (returns itself). */
    private class PlainFormat : SchemaAwareFormatModule {
        override val formatId = "plain-format"
        override val fileExtensions = setOf("plf")

        override suspend fun parseToJsonElement(content: String): JsonElement = JsonObject(emptyMap())

        override suspend fun parseExtendsOnly(content: String): JsonElement? = null
    }

    private suspend fun registryWith(vararg formats: ConfigFormatModule): ModuleRegistryImpl =
        ModuleRegistryImpl().apply {
            register(SchemaRule())
            formats.forEach { register(ConfigFormatModuleAdapter(it)) }
        }

    @Test
    fun `schema-aware format is registered as the instance withRuleSchemas returns`() =
        runTest {
            val original = JsonSchemaFormat()

            val registered = ConfigFormatRegistry.fromRegistry(registryWith(original)).getModule("json-schema-format")

            val schemas = checkNotNull((registered as JsonSchemaFormat).schemas)
            val rule = schemas.getValue("myRule").jsonObject
            val props = rule.getValue("properties").jsonObject
            assertEquals(JsonPrimitive("string"), props.getValue("pattern").jsonObject["type"])
            assertEquals(JsonPrimitive("integer"), props.getValue("max").jsonObject["type"])
            assertEquals(JsonPrimitive(72), props.getValue("max").jsonObject["default"])
            assertEquals(JsonPrimitive("array"), props.getValue("labels").jsonObject["type"])
            assertEquals(listOf(JsonPrimitive("pattern")), rule.getValue("required").jsonArray.toList())
            assertNull(original.schemas)
        }

    @Test
    fun `format without withRuleSchemas is registered unchanged`() =
        runTest {
            val plain = PlainFormat()

            val formats = ConfigFormatRegistry.fromRegistry(registryWith(plain))

            assertSame(plain, (formats.getModule("plain-format") as ConfigFormatModuleAdapter).delegate)
        }
}
