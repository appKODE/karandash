package io.karandash.config.pipeline

import io.karandash.api.InternalKarandashApi
import io.karandash.api.ModuleRegistryImpl
import io.karandash.api.PluginContext
import io.karandash.api.errors.ConfigException
import io.karandash.api.module.ProfileModule
import io.karandash.config.Config
import io.karandash.config.ProfileOverrides
import io.karandash.config.ProfileOverridesConfig
import io.karandash.config.ProfileRef
import io.karandash.config.RulesConfig
import io.karandash.config.TitleOverridesConfig
import io.karandash.config.merge.JsonElementMerger
import io.karandash.config.merge.MergeMode
import io.karandash.config.merge.MergeStrategyMap
import io.karandash.manifest.ExtendsEntry
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Parent/child layer merges across value types and merge-strategy modes, plus the edges around them: an invalid mode,
 * an explicit null, profile layer precedence and module-claimed top-level keys.
 */
@OptIn(InternalKarandashApi::class)
class ExtendsInheritanceMatrixTest {
    private fun merge(
        parent: JsonObject,
        child: JsonObject,
        strategy: MergeStrategyMap? = null,
    ): JsonObject = JsonElementMerger.mergeLayers(parent, child, strategy) as JsonObject

    private fun list(vararg items: String) = JsonArray(items.map(::JsonPrimitive))

    private fun named(
        name: String,
        value: Int,
    ) = buildJsonObject {
        put("name", name)
        put("value", value)
    }

    private fun mergedList(mode: MergeMode?): JsonElement? {
        val strategy = mode?.let { mapOf("items" to it) }
        return merge(
            buildJsonObject { put("items", list("a", "b")) },
            buildJsonObject { put("items", list("b", "c")) },
            strategy,
        )["items"]
    }

    @Test
    fun `a scalar from the child wins and a parent-only scalar survives`() {
        val merged = merge(
            buildJsonObject {
                put("baseline", "parent.json")
                put("kept", 1)
            },
            buildJsonObject { put("baseline", "child.json") },
        )
        assertEquals(JsonPrimitive("child.json"), merged["baseline"])
        assertEquals(JsonPrimitive(1), merged["kept"])
    }

    @Test
    fun `a plain list follows every merge mode`() {
        val expected = mapOf(
            null to list("b", "c"),
            MergeMode.REPLACE to list("b", "c"),
            MergeMode.APPEND to list("a", "b", "b", "c"),
            MergeMode.PREPEND to list("b", "c", "a", "b"),
            MergeMode.MERGE to list("a", "b", "c"),
        )
        expected.forEach { (mode, list) -> assertEquals(list, mergedList(mode), "mode $mode") }
    }

    @Test
    fun `a keyed list merges by name only under merge-by-key and is replaced by default`() {
        val parent = buildJsonObject {
            put("items", JsonArray(listOf(named("a", 1), named("b", 2))))
        }
        val child = buildJsonObject { put("items", JsonArray(listOf(named("b", 20), named("c", 3)))) }

        assertEquals(JsonArray(listOf(named("b", 20), named("c", 3))), merge(parent, child)["items"])
        assertEquals(
            JsonArray(listOf(named("a", 1), named("b", 20), named("c", 3))),
            merge(parent, child, mapOf("items" to MergeMode.MERGE_BY_KEY))["items"],
        )
    }

    @Test
    fun `a nested keyed array honours its dotted strategy path and not its leaf name`() {
        val parent = buildJsonObject { putJsonObject("a") { put("b", JsonArray(listOf(named("x", 1)))) } }
        val child = buildJsonObject { putJsonObject("a") { put("b", JsonArray(listOf(named("y", 2)))) } }

        val byPath = merge(parent, child, mapOf("a.b" to MergeMode.MERGE_BY_KEY))
        val byLeaf = merge(parent, child, mapOf("b" to MergeMode.MERGE_BY_KEY))

        assertEquals(JsonArray(listOf(named("x", 1), named("y", 2))), byPath["a"]?.jsonObject?.get("b"))
        assertEquals(JsonArray(listOf(named("y", 2))), byLeaf["a"]?.jsonObject?.get("b"))
    }

    @Test
    fun `policies merge by key without a strategy and a child policy replaces its namesake`() {
        val parent = buildJsonObject {
            putJsonArray("policies") {
                addJsonObject {
                    put("name", "p")
                    put("when", "a")
                }
                addJsonObject {
                    put("name", "q")
                    put("when", "b")
                }
            }
        }
        val child = buildJsonObject {
            putJsonArray("policies") {
                addJsonObject {
                    put("name", "p")
                    put("when", "c")
                }
            }
        }

        val policies = merge(parent, child)["policies"] as JsonArray

        assertEquals(listOf("c", "b"), policies.map { (it.jsonObject["when"] as JsonPrimitive).content })
    }

    @Test
    fun `an explicit null in the child clears baseline and dangerfile`() {
        val parent = buildJsonObject {
            put("baseline", "parent.json")
            put("dangerfile", "Dangerfile.kts")
        }
        val child = buildJsonObject {
            put("baseline", JsonNull)
            put("dangerfile", JsonNull)
        }

        val merged = merge(parent, child)
        val config = ConfigMapParser.parseFullConfig(merged).config

        assertEquals(JsonNull, merged["baseline"])
        assertEquals(JsonNull, merged["dangerfile"])
        assertNull(config.baseline)
        assertNull(config.dangerfile)
    }

    @Test
    fun `an unknown merge-strategy mode fails the extends entry`() {
        val data = buildJsonObject {
            putJsonArray("extends") {
                addJsonObject {
                    put("url", "https://example.com/base.yaml")
                    putJsonObject("merge-strategy") { put("items", "bogus") }
                }
            }
        }

        val error = assertFailsWith<ConfigException> { ExtendsParser.parse(data, emptyMap()) }

        assertTrue("'bogus'" in error.message.orEmpty(), error.message)
    }

    @Test
    fun `every merge-strategy mode spelling parses`() {
        val spellings = listOf("replace", "append", "prepend", "merge", "merge-by-key", "merge_by_key", "MERGE_BY_KEY")
        val data = buildJsonObject {
            putJsonArray("extends") {
                addJsonObject {
                    put("url", "https://example.com/base.yaml")
                    putJsonObject("merge-strategy") { spellings.forEach { put(it, it) } }
                }
            }
        }

        val entry: ExtendsEntry = ExtendsParser.parse(data, emptyMap()).single()

        assertEquals(spellings.associateWith { checkNotNull(MergeMode.fromString(it)) }, entry.mergeStrategy)
    }

    @Test
    fun `user rules beat the profile ref overrides which beat profileOverrides which beat the profile baseline`() =
        runTest {
            val profile = StubProfile(
                mapOf(
                    "titleFormat" to buildJsonObject {
                        put("pattern", "baseline")
                        put("minLength", 1)
                        put("maxLength", 10)
                        put("minWords", 1)
                    },
                ),
            )
            val registry = ModuleRegistryImpl().apply { register(profile) }
            val user = buildJsonObject { put("minWords", 4) }
            val ref = ProfileRef(
                id = profile.profileId,
                overrides = ProfileOverrides(rules = mapOf("titleFormat" to buildJsonObject { put("maxLength", 40) })),
            )
            val config = Config(
                rules = RulesConfig(ruleConfigs = mapOf("titleFormat" to user)),
                profileOverrides = ProfileOverridesConfig(title = TitleOverridesConfig(minLength = 5, maxLength = 30)),
            )

            val title = ProfileResolver(registry).apply(config, ref).rules.ruleConfigs["titleFormat"]

            val expected = buildJsonObject {
                put("pattern", "baseline")
                put("minLength", 5)
                put("maxLength", 40)
                put("minWords", 4)
            }
            assertEquals(expected, title)
        }

    @Test
    fun `a module-owned top-level key is claimed and a built-in or absent key is not`() {
        val root = buildJsonObject {
            putJsonObject("myExtension") { put("enabled", true) }
            putJsonObject("rules") { }
            put("unrelated", buildJsonArray { add(1) })
        }

        val claimed = UnknownTopLevelKeys.claimed(root, setOf("my-extension", "rules", "absent-block"))

        assertEquals(mapOf("my-extension" to root.getValue("myExtension")), claimed)
    }

    private class StubProfile(
        private val rules: Map<String, JsonElement>,
    ) : ProfileModule {
        override val profileId = "matrix-profile"
        override val id = "io.karandash.profile.$profileId"
        override val name = "Matrix Profile"
        override val version = "1.0.0"
        override val summary = "Inheritance matrix profile"

        override suspend fun initialize(context: PluginContext): Boolean = true

        override suspend fun ruleConfigs(): Map<String, JsonElement> = rules

        override suspend fun reporterConfigs(): Map<String, JsonElement> = emptyMap()

        override suspend fun policies(): List<JsonElement> = emptyList()
    }
}
