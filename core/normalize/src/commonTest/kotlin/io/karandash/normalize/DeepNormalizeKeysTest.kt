package io.karandash.normalize

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class DeepNormalizeKeysTest {
    @Test
    fun `top-level keys are normalized`() {
        val input = buildJsonObject {
            put("title-format", JsonPrimitive("value"))
            put("allow_localhost", JsonPrimitive(true))
            put("alreadyCamel", JsonPrimitive(1))
        }
        val result = deepNormalizeKeys(input) as JsonObject
        assertEquals(setOf("titleFormat", "allowLocalhost", "alreadyCamel"), result.keys)
    }

    @Test
    fun `nested keys are normalized`() {
        val input = buildJsonObject {
            put(
                "outer-key",
                buildJsonObject {
                    put("inner-key", JsonPrimitive("v"))
                }
            )
        }
        val result = deepNormalizeKeys(input) as JsonObject
        val inner = result["outerKey"] as JsonObject
        assertEquals(setOf("innerKey"), inner.keys)
    }

    @Test
    fun `deeply nested keys 3+ levels are normalized`() {
        val input = buildJsonObject {
            put(
                "level-one",
                buildJsonObject {
                    put(
                        "level-two",
                        buildJsonObject {
                            put("level-three", JsonPrimitive("deep"))
                        }
                    )
                }
            )
        }
        val result = deepNormalizeKeys(input) as JsonObject
        val l2 = result["levelOne"] as JsonObject
        val l3 = l2["levelTwo"] as JsonObject
        assertEquals(setOf("levelThree"), l3.keys)
        assertEquals(JsonPrimitive("deep"), l3["levelThree"])
    }

    @Test
    fun `array elements with objects are normalized`() {
        val input = buildJsonObject {
            put(
                "my-list",
                buildJsonArray {
                    add(buildJsonObject { put("item-key", JsonPrimitive("a")) })
                    add(buildJsonObject { put("other-key", JsonPrimitive("b")) })
                }
            )
        }
        val result = deepNormalizeKeys(input) as JsonObject
        val arr = result["myList"] as JsonArray
        assertEquals(setOf("itemKey"), (arr[0] as JsonObject).keys)
        assertEquals(setOf("otherKey"), (arr[1] as JsonObject).keys)
    }

    @Test
    fun `primitives pass through unchanged`() {
        val input = buildJsonObject {
            put("str-key", JsonPrimitive("hello"))
            put("num-key", JsonPrimitive(42))
            put("bool-key", JsonPrimitive(false))
        }
        val result = deepNormalizeKeys(input) as JsonObject
        assertEquals(JsonPrimitive("hello"), result["strKey"])
        assertEquals(JsonPrimitive(42), result["numKey"])
        assertEquals(JsonPrimitive(false), result["boolKey"])
    }

    @Test
    fun `preserved keys children are NOT normalized`() {
        val input = buildJsonObject {
            put(
                "headers",
                buildJsonObject {
                    put("X-Custom-Header", JsonPrimitive("val"))
                    put("Authorization", JsonPrimitive("Bearer token"))
                }
            )
        }
        val result = deepNormalizeKeys(input, preserveKeys = setOf("headers")) as JsonObject
        val headers = result["headers"] as JsonObject
        assertEquals(setOf("X-Custom-Header", "Authorization"), headers.keys)
    }

    @Test
    fun `preserved keys still normalize the parent key itself`() {
        val input = buildJsonObject {
            put(
                "x-headers",
                buildJsonObject {
                    put("X-Custom", JsonPrimitive("val"))
                }
            )
        }
        val result = deepNormalizeKeys(input, preserveKeys = setOf("xHeaders")) as JsonObject
        // Parent key "x-headers" -> "xHeaders" (normalized)
        val headers = result["xHeaders"] as JsonObject
        // Child keys preserved as-is
        assertEquals(setOf("X-Custom"), headers.keys)
    }

    @Test
    fun `preserved keys values at deeper nesting still normalized`() {
        // Inside a preserved key's children, if there is a nested object, its keys ARE normalized
        val input = buildJsonObject {
            put(
                "patterns",
                buildJsonObject {
                    put(
                        "my-pattern",
                        buildJsonObject {
                            put("match-type", JsonPrimitive("regex"))
                        }
                    )
                }
            )
        }
        val result = deepNormalizeKeys(input, preserveKeys = setOf("patterns")) as JsonObject
        val patterns = result["patterns"] as JsonObject
        // User-defined key "my-pattern" preserved
        val patternValue = patterns["my-pattern"] as JsonObject
        // Schema keys inside each pattern entry ARE normalized
        assertEquals(setOf("matchType"), patternValue.keys)
    }

    @Test
    fun `empty object and empty array pass through`() {
        val input = buildJsonObject {
            put("empty-obj", JsonObject(emptyMap()))
            put("empty-arr", JsonArray(emptyList()))
        }
        val result = deepNormalizeKeys(input) as JsonObject
        assertEquals(JsonObject(emptyMap()), result["emptyObj"])
        assertEquals(JsonArray(emptyList()), result["emptyArr"])
    }

    @Test
    fun `mixed scenario some keys preserved some normalized`() {
        val input = buildJsonObject {
            put("rule-id", JsonPrimitive("my-rule"))
            put(
                "http-headers",
                buildJsonObject {
                    put("Content-Type", JsonPrimitive("application/json"))
                    put("x-api-key", JsonPrimitive("secret"))
                }
            )
            put(
                "nested-config",
                buildJsonObject {
                    put("max-size", JsonPrimitive(100))
                }
            )
        }
        val result = deepNormalizeKeys(input, preserveKeys = setOf("httpHeaders")) as JsonObject
        // Top-level keys normalized
        assertEquals(JsonPrimitive("my-rule"), result["ruleId"])
        // Preserved parent key normalized, but children untouched
        val headers = result["httpHeaders"] as JsonObject
        assertEquals(setOf("Content-Type", "x-api-key"), headers.keys)
        // Non-preserved nested config normalized
        val nested = result["nestedConfig"] as JsonObject
        assertEquals(setOf("maxSize"), nested.keys)
    }

    // ── SCREAMING_SNAKE and consecutive separator edge cases ────────────

    @Test
    fun `SCREAMING_SNAKE keys normalized at nested levels`() {
        val input = buildJsonObject {
            put(
                "OUTER_KEY",
                buildJsonObject {
                    put(
                        "INNER_KEY",
                        buildJsonObject {
                            put("DEEP_KEY", JsonPrimitive("value"))
                        }
                    )
                }
            )
        }
        val result = deepNormalizeKeys(input) as JsonObject
        // SCREAMING_SNAKE: separator removed, chars preserved as-is (already uppercase)
        val outer = result["OUTERKEY"] as JsonObject
        val inner = outer["INNERKEY"] as JsonObject
        assertEquals(setOf("DEEPKEY"), inner.keys)
        assertEquals(JsonPrimitive("value"), inner["DEEPKEY"])
    }

    @Test
    fun `consecutive separator keys handled at all depths`() {
        val input = buildJsonObject {
            put(
                "top--key",
                buildJsonObject {
                    put(
                        "mid__key",
                        buildJsonObject {
                            put("deep-_key", JsonPrimitive("value"))
                        }
                    )
                }
            )
        }
        val result = deepNormalizeKeys(input) as JsonObject
        val top = result["topKey"] as JsonObject
        val mid = top["midKey"] as JsonObject
        assertEquals(setOf("deepKey"), mid.keys)
        assertEquals(JsonPrimitive("value"), mid["deepKey"])
    }
}
