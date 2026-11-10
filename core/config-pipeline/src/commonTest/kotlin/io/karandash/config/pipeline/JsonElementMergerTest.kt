package io.karandash.config.pipeline

import io.karandash.config.merge.MergeMode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class JsonElementMergerTest {
    @Test
    fun `merge empty objects returns empty`() {
        assertEquals(
            JsonObject(emptyMap()),
            JsonElementMerger.mergeLayers(JsonObject(emptyMap()), JsonObject(emptyMap())),
        )
    }

    @Test
    fun `override scalar wins over base`() {
        val base = JsonObject(mapOf("key" to JsonPrimitive("base-value")))
        val override = JsonObject(mapOf("key" to JsonPrimitive("override-value")))
        val result = JsonElementMerger.mergeLayers(base, override) as JsonObject
        assertEquals(JsonPrimitive("override-value"), result["key"])
    }

    @Test
    fun `base keys preserved when not in override`() {
        val base = JsonObject(mapOf("a" to JsonPrimitive("1"), "b" to JsonPrimitive("2")))
        val override = JsonObject(mapOf("a" to JsonPrimitive("updated")))
        val result = JsonElementMerger.mergeLayers(base, override) as JsonObject
        assertEquals(JsonPrimitive("updated"), result["a"])
        assertEquals(JsonPrimitive("2"), result["b"])
    }

    @Test
    fun `nested objects are recursively merged`() {
        val base = JsonObject(
            mapOf(
                "rules" to JsonObject(
                    mapOf("title-format" to JsonObject(mapOf("enabled" to JsonPrimitive(true)))),
                ),
            ),
        )
        val override = JsonObject(
            mapOf(
                "rules" to JsonObject(
                    mapOf("draft-prevention" to JsonObject(mapOf("enabled" to JsonPrimitive(true)))),
                ),
            ),
        )
        val result = JsonElementMerger.mergeLayers(base, override) as JsonObject
        val rules = result["rules"] as JsonObject
        assertEquals(2, rules.size)
    }

    @Test
    fun `list default strategy is REPLACE`() {
        val base = JsonObject(mapOf("items" to JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b")))))
        val override = JsonObject(mapOf("items" to JsonArray(listOf(JsonPrimitive("c")))))
        val result = JsonElementMerger.mergeLayers(base, override) as JsonObject
        assertEquals(JsonArray(listOf(JsonPrimitive("c"))), result["items"])
    }

    @Test
    fun `list APPEND strategy appends child to parent`() {
        val base = JsonObject(mapOf("items" to JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b")))))
        val override = JsonObject(mapOf("items" to JsonArray(listOf(JsonPrimitive("c")))))
        val strategy = mapOf("items" to MergeMode.APPEND)
        val result = JsonElementMerger.mergeLayers(base, override, strategy) as JsonObject
        assertEquals(
            JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b"), JsonPrimitive("c"))),
            result["items"],
        )
    }

    @Test
    fun `list PREPEND strategy prepends child to parent`() {
        val base = JsonObject(mapOf("items" to JsonArray(listOf(JsonPrimitive("b")))))
        val override = JsonObject(mapOf("items" to JsonArray(listOf(JsonPrimitive("a")))))
        val strategy = mapOf("items" to MergeMode.PREPEND)
        val result = JsonElementMerger.mergeLayers(base, override, strategy) as JsonObject
        assertEquals(JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b"))), result["items"])
    }

    @Test
    fun `null override value nullifies base value`() {
        val base = JsonObject(mapOf("key" to JsonPrimitive("value")))
        val override = JsonObject(mapOf("key" to JsonNull))
        val result = JsonElementMerger.mergeLayers(base, override) as JsonObject
        assertEquals(JsonNull, result["key"])
    }

    @Test
    fun `override null base returns override value`() {
        val base = JsonObject(emptyMap())
        val override = JsonObject(mapOf("key" to JsonPrimitive("new-value")))
        val result = JsonElementMerger.mergeLayers(base, override) as JsonObject
        assertEquals(JsonPrimitive("new-value"), result["key"])
    }

    // ── Type mismatch edge cases ────────────────────────────────────────

    @Test
    fun `type mismatch string vs object, override wins`() {
        val base = JsonObject(mapOf("key" to JsonPrimitive("string")))
        val override = JsonObject(mapOf("key" to JsonObject(mapOf("nested" to JsonPrimitive(true)))))
        val result = JsonElementMerger.mergeLayers(base, override) as JsonObject
        val nested = result["key"] as JsonObject
        assertEquals(JsonPrimitive(true), nested["nested"])
    }

    @Test
    fun `type mismatch object vs array, override wins`() {
        val base = JsonObject(mapOf("key" to JsonObject(mapOf("a" to JsonPrimitive(1)))))
        val override = JsonObject(mapOf("key" to JsonArray(listOf(JsonPrimitive(1), JsonPrimitive(2)))))
        val result = JsonElementMerger.mergeLayers(base, override) as JsonObject
        assertEquals(JsonArray(listOf(JsonPrimitive(1), JsonPrimitive(2))), result["key"])
    }

    // ── Null handling ───────────────────────────────────────────────────

    @Test
    fun `null override at nested level clears value`() {
        val base = JsonObject(mapOf("a" to JsonObject(mapOf("b" to JsonPrimitive("value")))))
        val override = JsonObject(mapOf("a" to JsonObject(mapOf("b" to JsonNull))))
        val result = JsonElementMerger.mergeLayers(base, override) as JsonObject
        val inner = result["a"] as JsonObject
        assertEquals(JsonNull, inner["b"])
    }

    // ── Deep merge preservation ─────────────────────────────────────────

    @Test
    fun `deeply nested merge preserves base keys at all levels`() {
        val base = JsonObject(
            mapOf(
                "l1" to JsonObject(
                    mapOf(
                        "baseL1" to JsonPrimitive("keep"),
                        "l2" to JsonObject(
                            mapOf(
                                "baseL2" to JsonPrimitive("keep"),
                                "l3" to JsonObject(mapOf("baseL3" to JsonPrimitive("keep"))),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val override = JsonObject(
            mapOf(
                "l1" to JsonObject(
                    mapOf(
                        "newL1" to JsonPrimitive("added"),
                        "l2" to JsonObject(
                            mapOf(
                                "newL2" to JsonPrimitive("added"),
                                "l3" to JsonObject(mapOf("newL3" to JsonPrimitive("added"))),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val result = JsonElementMerger.mergeLayers(base, override) as JsonObject
        val l1 = result["l1"] as JsonObject
        assertEquals(JsonPrimitive("keep"), l1["baseL1"])
        assertEquals(JsonPrimitive("added"), l1["newL1"])
        val l2 = l1["l2"] as JsonObject
        assertEquals(JsonPrimitive("keep"), l2["baseL2"])
        assertEquals(JsonPrimitive("added"), l2["newL2"])
        val l3 = l2["l3"] as JsonObject
        assertEquals(JsonPrimitive("keep"), l3["baseL3"])
        assertEquals(JsonPrimitive("added"), l3["newL3"])
    }

    // ── Array strategy edge cases ───────────────────────────────────────

    @Test
    fun `empty array with APPEND strategy produces empty`() {
        val base = JsonObject(mapOf("items" to JsonArray(emptyList())))
        val override = JsonObject(mapOf("items" to JsonArray(emptyList())))
        val strategy = mapOf("items" to MergeMode.APPEND)
        val result = JsonElementMerger.mergeLayers(base, override, strategy) as JsonObject
        assertEquals(JsonArray(emptyList()), result["items"])
    }

    @Test
    fun `empty array with PREPEND strategy produces empty`() {
        val base = JsonObject(mapOf("items" to JsonArray(emptyList())))
        val override = JsonObject(mapOf("items" to JsonArray(emptyList())))
        val strategy = mapOf("items" to MergeMode.PREPEND)
        val result = JsonElementMerger.mergeLayers(base, override, strategy) as JsonObject
        assertEquals(JsonArray(emptyList()), result["items"])
    }

    @Test
    fun `MERGE array strategy deduplicates`() {
        val base = JsonObject(
            mapOf("items" to JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b")))),
        )
        val override = JsonObject(
            mapOf("items" to JsonArray(listOf(JsonPrimitive("b"), JsonPrimitive("c")))),
        )
        val strategy = mapOf("items" to MergeMode.MERGE)
        val result = JsonElementMerger.mergeLayers(base, override, strategy) as JsonObject
        assertEquals(
            JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b"), JsonPrimitive("c"))),
            result["items"],
        )
    }

    // ── Policies merge-by-key (built-in default) ─────────────────────────

    @Test
    fun `policies use MERGE_BY_KEY by default, unmatched base items preserved`() {
        val base = JsonObject(
            mapOf(
                "policies" to JsonArray(
                    listOf(
                        JsonObject(mapOf("name" to JsonPrimitive("a"), "when" to JsonPrimitive("cond-a"))),
                        JsonObject(mapOf("name" to JsonPrimitive("b"), "when" to JsonPrimitive("cond-b"))),
                    ),
                ),
            ),
        )
        val override = JsonObject(
            mapOf(
                "policies" to JsonArray(
                    listOf(
                        JsonObject(mapOf("name" to JsonPrimitive("a"), "when" to JsonPrimitive("cond-a-new"))),
                    ),
                ),
            ),
        )
        val result = JsonElementMerger.mergeLayers(base, override) as JsonObject
        val policies = result["policies"] as JsonArray
        assertEquals(2, policies.size, "unmatched base policy 'b' should be preserved")
    }

    @Test
    fun `policies merge-by-key deep-merges matched items by name`() {
        val base = JsonObject(
            mapOf(
                "policies" to JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "name" to JsonPrimitive("changelog"),
                                "when" to JsonPrimitive("has_ticket"),
                                "override" to JsonObject(
                                    mapOf(
                                        "commit-body" to JsonObject(
                                            mapOf(
                                                "pattern" to JsonPrimitive("^CHANGELOG:"),
                                                "suggestion" to JsonPrimitive("base suggestion"),
                                            ),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val override = JsonObject(
            mapOf(
                "policies" to JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "name" to JsonPrimitive("changelog"),
                                "when" to JsonPrimitive("has_ticket && has_tag(android)"),
                                "override" to JsonObject(
                                    mapOf(
                                        "commit-body" to JsonObject(
                                            mapOf("explanation" to JsonPrimitive("android only")),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val result = JsonElementMerger.mergeLayers(base, override) as JsonObject
        val policies = result["policies"] as JsonArray
        assertEquals(1, policies.size)
        val policy = policies[0] as JsonObject
        // when is overridden
        assertEquals("has_ticket && has_tag(android)", (policy["when"] as JsonPrimitive).content)
        // override block is deep-merged: base pattern preserved, new explanation added
        val commitBody = (policy["override"] as JsonObject)["commit-body"] as JsonObject
        assertEquals("^CHANGELOG:", (commitBody["pattern"] as JsonPrimitive).content)
        assertEquals("base suggestion", (commitBody["suggestion"] as JsonPrimitive).content)
        assertEquals("android only", (commitBody["explanation"] as JsonPrimitive).content)
    }

    @Test
    fun `policies merge-by-key falls back to when field when name absent`() {
        val base = JsonObject(
            mapOf(
                "policies" to JsonArray(
                    listOf(
                        JsonObject(mapOf("when" to JsonPrimitive("is_draft"), "enabled" to JsonPrimitive(false))),
                    ),
                ),
            ),
        )
        val override = JsonObject(
            mapOf(
                "policies" to JsonArray(
                    listOf(
                        JsonObject(
                            mapOf(
                                "when" to JsonPrimitive("is_draft"),
                                "enabled" to JsonPrimitive(false),
                                "severity" to JsonPrimitive("info"),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val result = JsonElementMerger.mergeLayers(base, override) as JsonObject
        val policies = result["policies"] as JsonArray
        assertEquals(1, policies.size)
        val policy = policies[0] as JsonObject
        assertEquals("info", (policy["severity"] as JsonPrimitive).content)
    }

    @Test
    fun `policies merge-by-key appends new override items`() {
        val base = JsonObject(
            mapOf(
                "policies" to JsonArray(
                    listOf(
                        JsonObject(mapOf("name" to JsonPrimitive("existing"), "when" to JsonPrimitive("cond"))),
                    ),
                ),
            ),
        )
        val override = JsonObject(
            mapOf(
                "policies" to JsonArray(
                    listOf(
                        JsonObject(mapOf("name" to JsonPrimitive("new-policy"), "when" to JsonPrimitive("other"))),
                    ),
                ),
            ),
        )
        val result = JsonElementMerger.mergeLayers(base, override) as JsonObject
        val policies = result["policies"] as JsonArray
        assertEquals(2, policies.size, "new policy should be appended")
    }

    @Test
    fun `policies REPLACE can be forced via explicit strategy`() {
        val base = JsonObject(
            mapOf(
                "policies" to JsonArray(
                    listOf(
                        JsonObject(mapOf("name" to JsonPrimitive("a"), "when" to JsonPrimitive("cond-a"))),
                        JsonObject(mapOf("name" to JsonPrimitive("b"), "when" to JsonPrimitive("cond-b"))),
                    ),
                ),
            ),
        )
        val override = JsonObject(
            mapOf(
                "policies" to JsonArray(
                    listOf(
                        JsonObject(mapOf("name" to JsonPrimitive("c"), "when" to JsonPrimitive("cond-c"))),
                    ),
                ),
            ),
        )
        val strategy = mapOf("policies" to MergeMode.REPLACE)
        val result = JsonElementMerger.mergeLayers(base, override, strategy) as JsonObject
        val policies = result["policies"] as JsonArray
        assertEquals(1, policies.size, "explicit REPLACE should override built-in MERGE_BY_KEY")
    }

    // ── Scalar override edge cases ──────────────────────────────────────

    @Test
    fun `scalar values, override wins for all primitive types`() {
        val base = JsonObject(
            mapOf(
                "bool" to JsonPrimitive(false),
                "num" to JsonPrimitive(1),
                "str" to JsonPrimitive("old"),
            ),
        )
        val override = JsonObject(
            mapOf(
                "bool" to JsonPrimitive(true),
                "num" to JsonPrimitive(99),
                "str" to JsonPrimitive("new"),
            ),
        )
        val result = JsonElementMerger.mergeLayers(base, override) as JsonObject
        assertEquals(JsonPrimitive(true), result["bool"])
        assertEquals(JsonPrimitive(99), result["num"])
        assertEquals(JsonPrimitive("new"), result["str"])
    }
}
