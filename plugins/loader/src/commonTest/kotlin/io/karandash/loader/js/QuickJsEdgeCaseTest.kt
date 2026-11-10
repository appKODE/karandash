package io.karandash.loader.js

import com.dokar.quickjs.QuickJs
import io.karandash.api.PluginContext
import io.karandash.api.module.ReporterModule
import io.karandash.api.module.RuleModule
import io.karandash.api.support.mapToJsonElement
import io.karandash.models.finding.Severity
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QuickJsEdgeCaseTest {
    private lateinit var engine: QuickJs

    @BeforeTest
    fun setup() {
        engine = QuickJs.create(Dispatchers.Default)
    }

    @AfterTest
    fun teardown() {
        engine.close()
    }

    @Test
    fun testValidateReturnsNull() =
        runTest {
            val source = """
            export default {
                id: "test",
                name: "Test",
                version: "1.0.0",
                type: "RULE",
                validate() {
                    return null;
                }
            };
            """.trimIndent()

            val bridge = JsModuleBridge.fromSource(engine, source, "test.mjs")
            val findings = (bridge as RuleModule).validate("rule", createTestMergeRequest(), JsonObject(emptyMap()))

            assertTrue(findings.isEmpty())
        }

    @Test
    fun testValidateReturnsUndefined() =
        runTest {
            val source = """
            export default {
                id: "test",
                name: "Test",
                version: "1.0.0",
                type: "RULE",
                validate() {
                    return undefined;
                }
            };
            """.trimIndent()

            val bridge = JsModuleBridge.fromSource(engine, source, "test.mjs")
            val findings = (bridge as RuleModule).validate("rule", createTestMergeRequest(), JsonObject(emptyMap()))

            assertTrue(findings.isEmpty())
        }

    @Test
    fun testValidateReturnsEmptyArray() =
        runTest {
            val source = """
            export default {
                id: "test",
                name: "Test",
                version: "1.0.0",
                type: "RULE",
                validate() {
                    return [];
                }
            };
            """.trimIndent()

            val bridge = JsModuleBridge.fromSource(engine, source, "test.mjs")
            val findings = (bridge as RuleModule).validate("rule", createTestMergeRequest(), JsonObject(emptyMap()))

            assertTrue(findings.isEmpty())
        }

    @Test
    fun testInvalidFieldTypesIdIsNumber() =
        runTest {
            val source = """
            export default {
                id: 12345,
                name: "Test",
                version: "1.0.0",
                type: "RULE"
            };
            """.trimIndent()

            // Should convert number to string gracefully
            val bridge = JsModuleBridge.fromSource(engine, source, "test.mjs")
            assertEquals("12345", bridge.id)
        }

    @Test
    fun testInvalidFieldTypesMissingId() =
        runTest {
            val source = """
            export default {
                name: "Test",
                version: "1.0.0",
                type: "RULE"
            };
            """.trimIndent()

            // Should fail with clear error
            val exception = assertFails {
                JsModuleBridge.fromSource(engine, source, "test.mjs")
            }
            assertTrue(exception.message?.contains("missing required field: id") == true)
        }

    @Test
    fun testInvalidFieldTypesMissingName() =
        runTest {
            val source = """
            export default {
                id: "test",
                version: "1.0.0",
                type: "RULE"
            };
            """.trimIndent()

            // Should fail with clear error
            val exception = assertFails {
                JsModuleBridge.fromSource(engine, source, "test.mjs")
            }
            assertTrue(exception.message?.contains("missing required field: name") == true)
        }

    @Test
    fun testShutdownThrows() =
        runTest {
            val source = """
            export default {
                id: "test",
                name: "Test",
                version: "1.0.0",
                type: "RULE",
                shutdown() {
                    throw new Error("Shutdown failed");
                }
            };
            """.trimIndent()

            val bridge = JsModuleBridge.fromSource(engine, source, "test.mjs")

            // Should not throw - shutdown is best-effort
            bridge.shutdown()
            // If we reach here, the test passes
        }

    @Test
    fun testValidateThrows() =
        runTest {
            val source = """
            export default {
                id: "test",
                name: "Test",
                version: "1.0.0",
                type: "RULE",
                validate() {
                    throw new Error("Validation failed");
                }
            };
            """.trimIndent()

            val bridge = JsModuleBridge.fromSource(engine, source, "test.mjs")

            // Should return empty list on exception
            val findings = (bridge as RuleModule).validate("rule", createTestMergeRequest(), JsonObject(emptyMap()))
            assertTrue(findings.isEmpty())
        }

    @Test
    fun testLargeNestedConfig() =
        runTest {
            val source = """
            export default {
                id: "test",
                name: "Test",
                version: "1.0.0",
                type: "RULE",
                validate(ruleId, mr, config) {
                    // Access deeply nested config
                    const nested = config.level1.level2.level3.level4;
                    if (nested !== "deep value") {
                        throw new Error("Config not serialized correctly");
                    }
                    return [];
                }
            };
            """.trimIndent()

            val bridge = JsModuleBridge.fromSource(engine, source, "test.mjs")
            val config = mapOf(
                "level1" to mapOf(
                    "level2" to mapOf(
                        "level3" to mapOf(
                            "level4" to "deep value"
                        )
                    )
                )
            )

            val findings = (bridge as RuleModule).validate("rule", createTestMergeRequest(), mapToJsonElement(config))
            assertTrue(findings.isEmpty())
        }

    @Test
    fun testConfigWithMixedTypes() =
        runTest {
            val source = """
            export default {
                id: "test",
                name: "Test",
                version: "1.0.0",
                type: "RULE",
                validate(ruleId, mr, config) {
                    // Verify all types preserved
                    if (config.str !== "text") throw new Error("String not preserved");
                    if (config.num !== 42) throw new Error("Number not preserved");
                    if (config.bool !== true) throw new Error("Boolean not preserved");
                    if (config.nullVal !== null) throw new Error("Null not preserved");
                    if (!Array.isArray(config.arr)) throw new Error("Array not preserved");
                    if (typeof config.nested !== "object") throw new Error("Object not preserved");
                    return [];
                }
            };
            """.trimIndent()

            val bridge = JsModuleBridge.fromSource(engine, source, "test.mjs")
            val config = mapOf(
                "str" to "text",
                "num" to 42,
                "bool" to true,
                "nullVal" to null,
                "arr" to listOf(1, 2, 3),
                "nested" to mapOf("key" to "value")
            )

            val findings = (bridge as RuleModule).validate("rule", createTestMergeRequest(), mapToJsonElement(config))
            assertTrue(findings.isEmpty())
        }

    @Test
    fun testFindingWithAllOptionalFields() {
        val json = """
            [{
                "severity": "ERROR",
                "message": "Test message",
                "rule": "test-rule",
                "location": {
                    "type": "file",
                    "path": "test.kt",
                    "line": 10
                },
                "explanation": "Detailed explanation of the issue",
                "metadata": {
                    "key1": "value1",
                    "key2": "value2"
                }
            }]
        """

        val findings = JsTypeConverter.findingsFromJson(json)
        assertEquals(1, findings.size)

        val finding = findings[0]
        assertEquals(Severity.ERROR, finding.severity)
        assertEquals("Test message", finding.message)
        assertEquals("test-rule", finding.rule)

        // Verify all optional fields are present
        val loc = finding.location as? io.karandash.models.finding.Location.FileRef
        assertNotNull(loc)
        assertEquals("test.kt", loc.path)
        assertEquals(10, loc.line)
        assertNotNull(finding.explanation)
        assertEquals("Detailed explanation of the issue", finding.explanation)
        assertEquals(2, finding.metadata.size)
        assertEquals("value1", finding.metadata["key1"])
        assertEquals("value2", finding.metadata["key2"])
    }

    @Test
    fun testFindingWithMinimalFields() {
        val json = """
            [{
                "severity": "WARNING",
                "message": "Warning message",
                "rule": "warning-rule"
            }]
        """

        val findings = JsTypeConverter.findingsFromJson(json)
        assertEquals(1, findings.size)

        val finding = findings[0]
        assertEquals(Severity.WARNING, finding.severity)
        assertEquals("Warning message", finding.message)
        assertEquals("warning-rule", finding.rule)

        // Verify optional fields have defaults
        assertNull(finding.location)
        assertNull(finding.explanation)
        assertTrue(finding.metadata.isEmpty())
    }

    @Test
    fun testFindingWithPartialLocation() {
        val json = """
            [{
                "severity": "INFO",
                "message": "Info message",
                "rule": "info-rule",
                "location": {
                    "type": "file",
                    "path": "test.kt"
                }
            }]
        """

        val findings = JsTypeConverter.findingsFromJson(json)
        assertEquals(1, findings.size)

        val finding = findings[0]
        assertNotNull(finding.location)
        val loc = finding.location as? io.karandash.models.finding.Location.FileRef
        assertNotNull(loc)
        assertEquals("test.kt", loc.path)
        assertNull(loc.line)
    }

    @Test
    fun testContextEnvReturnsNullWhenVariableMissing() =
        runTest {
            val context = object : PluginContext {
                override val pluginConfig: JsonElement = JsonObject(emptyMap())
                override val hostVersion: String = "1.0.0"
                override val workingDirectory: String = "/test"

                override fun log(message: String) {}

                override fun warn(message: String) {}

                override fun env(name: String): String? = null
            }

            val injector = JsContextInjector(engine, context)
            injector.inject()

            val result = engine.evaluate<Any?>("__karandash_env('NONEXISTENT')")
            assertNull(result)
        }

    @Test
    fun testInvalidModuleType() =
        runTest {
            val source = """
            export default {
                id: "test",
                name: "Test",
                version: "1.0.0",
                type: "INVALID_TYPE"
            };
            """.trimIndent()

            val exception = assertFails {
                JsModuleBridge.fromSource(engine, source, "test.mjs")
            }
            assertTrue(exception.message?.contains("invalid type") == true)
            assertTrue(exception.message?.contains("INVALID_TYPE") == true)
        }

    @Test
    fun testContributedRulesReturnsNull() =
        runTest {
            val source = """
            export default {
                id: "test",
                name: "Test",
                version: "1.0.0",
                type: "RULE",
                contributedRules() {
                    return null;
                }
            };
            """.trimIndent()

            val bridge = JsModuleBridge.fromSource(engine, source, "test.mjs")
            val rules = (bridge as RuleModule).contributedRules()

            // Should return empty set on null
            assertTrue(rules.isEmpty())
        }

    @Test
    fun testContributedRulesThrows() =
        runTest {
            val source = """
            export default {
                id: "test",
                name: "Test",
                version: "1.0.0",
                type: "RULE",
                contributedRules() {
                    throw new Error("Failed to get rules");
                }
            };
            """.trimIndent()

            val bridge = JsModuleBridge.fromSource(engine, source, "test.mjs")
            val rules = (bridge as RuleModule).contributedRules()

            // Should return empty set on exception
            assertTrue(rules.isEmpty())
        }

    @Test
    fun testReportReturnsNull() =
        runTest {
            val source = """
            export default {
                id: "test",
                name: "Test",
                version: "1.0.0",
                type: "REPORTER",
                formatId: "json",
                report() {
                    return null;
                }
            };
            """.trimIndent()

            val bridge = JsModuleBridge.fromSource(engine, source, "test.mjs")
            val result = (bridge as ReporterModule).report(emptyList(), true, JsonObject(emptyMap()))

            assertNull(result)
        }

    @Test
    fun testReportReturnsUndefined() =
        runTest {
            val source = """
            export default {
                id: "test",
                name: "Test",
                version: "1.0.0",
                type: "REPORTER",
                formatId: "json",
                report() {
                    return undefined;
                }
            };
            """.trimIndent()

            val bridge = JsModuleBridge.fromSource(engine, source, "test.mjs")
            val result = (bridge as ReporterModule).report(emptyList(), true, JsonObject(emptyMap()))

            assertNull(result)
        }

    @Test
    fun testReportThrows() =
        runTest {
            val source = """
            export default {
                id: "test",
                name: "Test",
                version: "1.0.0",
                type: "REPORTER",
                formatId: "json",
                report() {
                    throw new Error("Report failed");
                }
            };
            """.trimIndent()

            val bridge = JsModuleBridge.fromSource(engine, source, "test.mjs")
            val result = (bridge as ReporterModule).report(emptyList(), true, JsonObject(emptyMap()))

            // Should return null on exception
            assertNull(result)
        }

    @Test
    fun testInitializeReturnsFalse() =
        runTest {
            val source = """
            export default {
                id: "test",
                name: "Test",
                version: "1.0.0",
                type: "RULE",
                initialize() {
                    return false;
                }
            };
            """.trimIndent()

            val bridge = JsModuleBridge.fromSource(engine, source, "test.mjs")
            val context = createTestContext()

            val result = bridge.initialize(context)
            assertFalse(result)
        }

    @Test
    fun testInitializeThrows() =
        runTest {
            val source = """
            export default {
                id: "test",
                name: "Test",
                version: "1.0.0",
                type: "RULE",
                initialize() {
                    throw new Error("Init failed");
                }
            };
            """.trimIndent()

            val bridge = JsModuleBridge.fromSource(engine, source, "test.mjs")
            val context = createTestContext()

            // Should return false on exception
            val result = bridge.initialize(context)
            assertFalse(result)
        }

    // Helper functions

    private fun createTestContext() =
        object : PluginContext {
            override val pluginConfig: JsonElement = kotlinx.serialization.json.JsonObject(emptyMap())
            override val hostVersion: String = "1.0.0"
            override val workingDirectory: String = "/test"

            override fun log(message: String) {}

            override fun warn(message: String) {}

            override fun env(name: String): String? = null
        }
}
