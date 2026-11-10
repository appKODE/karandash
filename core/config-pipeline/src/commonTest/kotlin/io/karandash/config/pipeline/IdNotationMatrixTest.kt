package io.karandash.config.pipeline

import io.karandash.api.InternalKarandashApi
import io.karandash.api.ModuleRegistryImpl
import io.karandash.config.Config
import io.karandash.config.RulesConfig
import io.karandash.logger.Logger
import io.karandash.models.finding.FindingField
import io.karandash.normalize.ModuleIdNormalizer
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every config site that names a module, rule or field by id accepts the id in any notation (camel, kebab, snake,
 * UPPER, dotted) and resolves it to the one canonical id; schema keys nested under an id normalize at any depth.
 */
@OptIn(InternalKarandashApi::class)
class IdNotationMatrixTest {
    private class RecordingLogger : Logger {
        val warnings = mutableListOf<String>()

        override fun debug(message: String) = Unit

        override fun info(message: String) = Unit

        override fun warn(message: String) {
            warnings += message
        }

        override fun error(message: String) = Unit
    }

    private fun parse(
        root: JsonObject,
        logger: Logger = RecordingLogger(),
    ): Config = ConfigMapParser.parseFullConfig(root, logger = logger).config

    private fun resolvesToCanonical(id: String): Boolean = ModuleIdNormalizer.resolve(id, listOf(CANONICAL)) == CANONICAL

    /** Fails with every spelling whose resolved id from [idOf] is not the canonical one. */
    private fun assertEverySpelling(idOf: (String) -> String?) {
        val failures = ID_SPELLINGS.mapNotNull { spelling ->
            val id = idOf(spelling)
            "$spelling -> $id".takeUnless { id != null && resolvesToCanonical(id) }
        }
        assertEquals(emptyList(), failures)
    }

    @Test
    fun `rules ids in every notation resolve to the canonical id`() =
        assertEverySpelling { spelling ->
            parse(buildJsonObject { putJsonObject("rules") { putJsonObject(spelling) { put("max_lines", 5) } } })
                .rules.ruleConfigs.keys
                .singleOrNull()
        }

    @Test
    fun `rule-enrichment ids in every notation keep their spelling and resolve to the canonical id`() =
        assertEverySpelling { spelling ->
            val root = buildJsonObject {
                putJsonObject("rule-enrichment") { putJsonObject(spelling) { put("enrich", buildJsonArray { add("jira") }) } }
            }
            parse(root)
                .ruleEnrichment
                ?.keys
                ?.singleOrNull()
                ?.takeIf { it == spelling }
        }

    @Test
    fun `policy override ids in every notation resolve with schema keys normalized three levels deep`() {
        val nested = mutableListOf<String?>()
        assertEverySpelling { spelling ->
            val root = buildJsonObject {
                put(
                    "policies",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("when", "true")
                                putJsonObject("override") { putJsonObject(spelling) { nestedSchemaKeys() } }
                            },
                        )
                    },
                )
            }
            val override = parse(root).policies?.single()?.override
            override?.values?.single()?.let { nested.add(hardLimit(it)) }
            override?.keys?.singleOrNull()
        }
        assertEquals<List<String?>>(List(ID_SPELLINGS.size) { "100" }, nested)
    }

    @Test
    fun `policy severity filter module ids in every notation resolve and keep the severity`() {
        val severities = mutableListOf<String?>()
        assertEverySpelling { spelling ->
            val root = buildJsonObject {
                put(
                    "policies",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("when", "true")
                                put("modules", buildJsonArray { add(spelling) })
                                put("severity", "warning")
                            },
                        )
                    },
                )
            }
            val policy = parse(root).policies?.single()
            severities.add(policy?.severity)
            policy?.modules?.singleOrNull()
        }
        assertEquals<List<String?>>(List(ID_SPELLINGS.size) { "warning" }, severities)
    }

    @Test
    fun `rule when-override schema keys normalize three levels deep in every notation`() {
        val values = SCHEMA_SPELLINGS.map { (lineRules, wrapAt, hardLimit) ->
            val root = buildJsonObject {
                putJsonObject("rules") {
                    putJsonObject(CANONICAL) {
                        putJsonObject("when") {
                            put("expression", "true")
                            putJsonObject("override") { putJsonObject(lineRules) { putJsonObject(wrapAt) { put(hardLimit, 100) } } }
                        }
                    }
                }
            }
            val override = parse(root)
                .rules.ruleMeta.values
                .single()
                .`when`
                ?.override
            override?.let { hardLimit(it) }
        }
        assertEquals(List(SCHEMA_SPELLINGS.size) { "100" }, values)
    }

    @Test
    fun `ai analyzer ids in every notation resolve to the canonical id`() =
        assertEverySpelling { spelling ->
            val root = buildJsonObject {
                putJsonObject("ai") { putJsonObject("analyzers") { putJsonObject(spelling) { put("enabled", true) } } }
            }
            (parse(root).ai as? JsonObject)
                ?.get("analyzers")
                ?.jsonObject
                ?.keys
                ?.singleOrNull()
        }

    @Test
    fun `profile overrides parse alike in every key notation`() {
        val lengths = listOf("profileOverrides", "profile-overrides", "profile_overrides").flatMap { block ->
            listOf("maxLength", "max-length", "max_length").map { key ->
                parse(buildJsonObject { putJsonObject(block) { putJsonObject("title") { put(key, 50) } } })
                    .profileOverrides.title.maxLength
            }
        }
        assertEquals(List(lengths.size) { 50 }, lengths)
    }

    @Test
    fun `finding field names normalize exactly like module ids`() {
        val names = FindingField.entries.map { it.key } + ID_SPELLINGS + listOf("End Line", "end.line", "END-LINE", "endLine")
        names.forEach { name ->
            val viaField = FindingField.parse(listOf(name)).fields
            val viaId = FindingField.entries.filter { ModuleIdNormalizer.matches(it.key, name) && it.hideable }.toSet()
            assertEquals(viaId, viaField, name)
        }
    }

    @Test
    fun `unknown nested keys under profileOverrides and a rule-enrichment id warn`() =
        runTest {
            val root = buildJsonObject {
                putJsonObject("profile-overrides") { putJsonObject("title") { put("max-lenght", 50) } }
                putJsonObject("rule-enrichment") { putJsonObject("commit_body") { put("enrihc", buildJsonArray { }) } }
            }

            val warnings = UnknownNestedKeys.warnings(root, RulesConfig(), ModuleRegistryImpl())

            assertEquals(2, warnings.size, warnings.toString())
            assertTrue(warnings.any { "'profileOverrides.title.maxLenght'" in it }, warnings.toString())
            assertTrue(warnings.any { "'ruleEnrichment.commit_body.enrihc'" in it }, warnings.toString())
        }

    @Test
    fun `a rule set to a non-boolean scalar warns and is ignored`() {
        val logger = RecordingLogger()

        val config = parse(buildJsonObject { putJsonObject("rules") { put(CANONICAL, "on") } }, logger)

        assertTrue(config.rules.ruleConfigs.isEmpty())
        assertEquals(1, logger.warnings.count { "'rules.commitBody'" in it && "'on'" in it }, logger.warnings.toString())
    }

    private fun JsonObjectBuilder.nestedSchemaKeys() {
        put("max_lines", 5)
        putJsonObject("line-rules") { putJsonObject("wrap_at") { put("hard-limit", 100) } }
    }

    /** `lineRules.wrapAt.hardLimit` of an override rule config, read with the normalized camelCase keys. */
    private fun hardLimit(rule: JsonElement): String? =
        (((rule as? JsonObject)?.get("lineRules") as? JsonObject)?.get("wrapAt") as? JsonObject)
            ?.get("hardLimit")
            ?.let { (it as? JsonPrimitive)?.content }

    private companion object {
        const val CANONICAL = "commit-body"
        val ID_SPELLINGS = listOf("commitBody", "commit-body", "commit_body", "COMMIT_BODY", "commit.body")
        val SCHEMA_SPELLINGS = listOf(
            Triple("lineRules", "wrapAt", "hardLimit"),
            Triple("line-rules", "wrap-at", "hard-limit"),
            Triple("line_rules", "wrap_at", "hard_limit"),
        )
    }
}
