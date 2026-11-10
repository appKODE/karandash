package io.karandash.loader.js

import com.dokar.quickjs.QuickJs
import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import io.karandash.api.module.ReporterModule
import io.karandash.api.module.RuleModule
import io.karandash.api.support.mapToJsonElement
import io.karandash.models.finding.Finding
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JsModuleBridgeTest {
    private lateinit var engine: QuickJs

    @BeforeTest
    fun setup() {
        engine = QuickJs.create(Dispatchers.Default)
    }

    @AfterTest
    fun teardown() {
        engine.close()
    }

    // --- fromSource tests ---

    @Test
    fun testFromSourceValidRulePlugin() =
        runTest {
            val source = """
            module.exports = {
                id: "test-plugin",
                name: "Test Plugin",
                version: "1.0.0",
                type: "RULE"
            };
            """.trimIndent()

            val bridge = JsModuleBridge.fromSource(engine, source, "test.mjs")

            assertEquals("test-plugin", bridge.id)
            assertEquals("Test Plugin", bridge.name)
            assertEquals("1.0.0", bridge.version)
            assertEquals(ModuleType.RULE, bridge.type)
        }

    @Test
    fun testFromSourceValidReporterPlugin() =
        runTest {
            val source = """
            module.exports = {
                id: "test-reporter",
                name: "Test Reporter",
                version: "2.0.0",
                type: "REPORTER",
                formatId: "json"
            };
            """.trimIndent()

            val bridge = JsModuleBridge.fromSource(engine, source, "test.mjs")

            assertEquals("test-reporter", bridge.id)
            assertEquals("Test Reporter", bridge.name)
            assertEquals("2.0.0", bridge.version)
            assertEquals(ModuleType.REPORTER, bridge.type)
            assertEquals("json", (bridge as ReporterModule).formatId)
        }

    @Test
    fun testFromSourceCommonJsExports() =
        runTest {
            val source = """
            module.exports = {
                id: "commonjs-plugin",
                name: "CommonJS Plugin",
                version: "1.0.0",
                type: "RULE"
            };
            """.trimIndent()

            val bridge = JsModuleBridge.fromSource(engine, source, "test.js")

            assertEquals("commonjs-plugin", bridge.id)
            assertEquals("CommonJS Plugin", bridge.name)
        }

    @Test
    fun testFromSourceMissingIdThrows() =
        runTest {
            val source = """
            module.exports = {
                name: "Test",
                version: "1.0.0",
                type: "RULE"
            };
            """.trimIndent()

            val exception = assertFails {
                JsModuleBridge.fromSource(engine, source, "test.mjs")
            }
            assertTrue(exception.message!!.contains("missing required field: id"))
        }

    @Test
    fun testFromSourceMissingNameThrows() =
        runTest {
            val source = """
            module.exports = {
                id: "test-plugin",
                version: "1.0.0",
                type: "RULE"
            };
            """.trimIndent()

            val exception = assertFails {
                JsModuleBridge.fromSource(engine, source, "test.mjs")
            }
            assertTrue(exception.message!!.contains("missing required field: name"))
        }

    @Test
    fun testFromSourceInvalidTypeThrows() =
        runTest {
            val source = """
            module.exports = {
                id: "test",
                name: "Test",
                version: "1.0.0",
                type: "INVALID_TYPE"
            };
            """.trimIndent()

            val exception = assertFails {
                JsModuleBridge.fromSource(engine, source, "test.mjs")
            }
            assertTrue(exception.message!!.contains("invalid type"))
        }

    @Test
    fun testFromSourceDefaultVersion() =
        runTest {
            val source = """
            module.exports = {
                id: "test",
                name: "Test",
                type: "RULE"
            };
            """.trimIndent()

            val bridge = JsModuleBridge.fromSource(engine, source, "test.mjs")

            assertEquals("0.0.0", bridge.version)
        }

    @Test
    fun testFromSourceDefaultTypeIsRule() =
        runTest {
            val source = """
            module.exports = {
                id: "test",
                name: "Test"
            };
            """.trimIndent()

            val bridge = JsModuleBridge.fromSource(engine, source, "test.mjs")

            assertEquals(ModuleType.RULE, bridge.type)
        }

    // --- initialize tests ---

    @Test
    fun testInitializeWithoutFunctionReturnsTrue() =
        runTest {
            val source = """
            module.exports = {
                id: "test",
                name: "Test",
                version: "1.0.0",
                type: "RULE"
            };
            """.trimIndent()

            val bridge = JsModuleBridge.fromSource(engine, source, "test.mjs")
            val context = createTestContext()

            assertTrue(bridge.initialize(context))
        }

    @Test
    fun testInitializeReturnsTrue() =
        runTest {
            val source = """
            module.exports = {
                id: "test",
                name: "Test",
                version: "1.0.0",
                type: "RULE",
                initialize(context) {
                    return true;
                }
            };
            """.trimIndent()

            val bridge = JsModuleBridge.fromSource(engine, source, "test.mjs")
            val context = createTestContext()

            assertTrue(bridge.initialize(context))
        }

    @Test
    fun testInitializeReturnsFalse() =
        runTest {
            val source = """
            module.exports = {
                id: "test",
                name: "Test",
                version: "1.0.0",
                type: "RULE",
                initialize(context) {
                    return false;
                }
            };
            """.trimIndent()

            val bridge = JsModuleBridge.fromSource(engine, source, "test.mjs")
            val context = createTestContext()

            assertFalse(bridge.initialize(context))
        }

    @Test
    fun testInitializeErrorReturnsFalse() =
        runTest {
            val source = """
            module.exports = {
                id: "test",
                name: "Test",
                version: "1.0.0",
                type: "RULE",
                initialize(context) {
                    throw new Error("Initialization failed");
                }
            };
            """.trimIndent()

            val bridge = JsModuleBridge.fromSource(engine, source, "test.mjs")
            val context = createTestContext()

            assertFalse(bridge.initialize(context))
        }

    @Test
    fun testInitializeReceivesContext() =
        runTest {
            val source = """
            var receivedContext = null;
            module.exports = {
                id: "test",
                name: "Test",
                version: "1.0.0",
                type: "RULE",
                initialize(context) {
                    receivedContext = context;
                    return true;
                }
            };
            """.trimIndent()

            val bridge = JsModuleBridge.fromSource(engine, source, "test.mjs")
            val context = createTestContext()

            bridge.initialize(context)

            val hostVersion = engine.evaluate<String>("receivedContext.hostVersion")
            assertEquals("0.1.0-test", hostVersion)
        }

    /** The run shuts every rule module down and re-initializes it on the next (watch) run. */
    @Test
    fun testReinitializeAfterShutdownValidatesAgain() =
        runTest {
            val source = """
            var ready = false;
            module.exports = {
                id: "test",
                name: "Test",
                version: "1.0.0",
                type: "RULE",
                initialize(context) { ready = true; return true; },
                shutdown() { ready = false; },
                validate(ruleId, mr, config) {
                    return ready ? [{ severity: "ERROR", message: "ready", rule: ruleId }] : [];
                }
            };
            """.trimIndent()

            val bridge = JsModuleBridge.fromSource(engine, source, "test.mjs")
            val mr = createTestMergeRequest()
            assertTrue(bridge.initialize(createTestContext()))
            bridge.shutdown()
            assertTrue(bridge.validate("r", mr, JsonObject(emptyMap())).isEmpty())

            assertTrue(bridge.initialize(createTestContext()))
            assertEquals(listOf("ready"), bridge.validate("r", mr, JsonObject(emptyMap())).map { it.message })
        }

    // --- shutdown tests ---

    @Test
    fun testShutdownWithoutFunctionSucceeds() =
        runTest {
            val source = """
            module.exports = {
                id: "test",
                name: "Test",
                version: "1.0.0",
                type: "RULE"
            };
            """.trimIndent()

            val bridge = JsModuleBridge.fromSource(engine, source, "test.mjs")

            // Should not throw
            bridge.shutdown()
        }

    @Test
    fun testShutdownCallsFunction() =
        runTest {
            val source = """
            var shutdownCalled = false;
            module.exports = {
                id: "test",
                name: "Test",
                version: "1.0.0",
                type: "RULE",
                shutdown() {
                    shutdownCalled = true;
                }
            };
            """.trimIndent()

            val bridge = JsModuleBridge.fromSource(engine, source, "test.mjs")
            bridge.shutdown()

            val called = engine.evaluate<Boolean>("shutdownCalled")
            assertTrue(called)
        }

    @Test
    fun testShutdownErrorSwallowed() =
        runTest {
            val source = """
            module.exports = {
                id: "test",
                name: "Test",
                version: "1.0.0",
                type: "RULE",
                shutdown() {
                    throw new Error("Shutdown error");
                }
            };
            """.trimIndent()

            val bridge = JsModuleBridge.fromSource(engine, source, "test.mjs")

            // Should not throw
            bridge.shutdown()
        }

    // --- contributedRules tests ---

    @Test
    fun testContributedRulesWithoutFunctionReturnsEmpty() =
        runTest {
            val source = """
            module.exports = {
                id: "test",
                name: "Test",
                version: "1.0.0",
                type: "RULE"
            };
            """.trimIndent()

            val bridge = JsModuleBridge.fromSource(engine, source, "test.mjs")
            val rules = (bridge as RuleModule).contributedRules()

            assertTrue(rules.isEmpty())
        }

    @Test
    fun testContributedRulesReturnsRules() =
        runTest {
            val source = """
            module.exports = {
                id: "test",
                name: "Test",
                version: "1.0.0",
                type: "RULE",
                contributedRules() {
                    return ["rule1", "rule2", "rule3"];
                }
            };
            """.trimIndent()

            val bridge = JsModuleBridge.fromSource(engine, source, "test.mjs")
            val rules = (bridge as RuleModule).contributedRules()

            assertEquals(3, rules.size)
            assertTrue(rules.contains("rule1"))
            assertTrue(rules.contains("rule2"))
            assertTrue(rules.contains("rule3"))
        }

    @Test
    fun testContributedRulesErrorReturnsEmpty() =
        runTest {
            val source = """
            module.exports = {
                id: "test",
                name: "Test",
                version: "1.0.0",
                type: "RULE",
                contributedRules() {
                    throw new Error("Error getting rules");
                }
            };
            """.trimIndent()

            val bridge = JsModuleBridge.fromSource(engine, source, "test.mjs")
            val rules = (bridge as RuleModule).contributedRules()

            assertTrue(rules.isEmpty())
        }

    // --- validate tests ---

    @Test
    fun testValidateWithoutFunctionReturnsEmpty() =
        runTest {
            val source = """
            module.exports = {
                id: "test",
                name: "Test",
                version: "1.0.0",
                type: "RULE"
            };
            """.trimIndent()

            val bridge = JsModuleBridge.fromSource(engine, source, "test.mjs")
            val mr = createTestMergeRequest()

            val findings = (bridge as RuleModule).validate("test-rule", mr, JsonObject(emptyMap()))
            assertTrue(findings.isEmpty())
        }

    @Test
    fun testValidateReturnsFindings() =
        runTest {
            val source = """
            module.exports = {
                id: "test",
                name: "Test",
                version: "1.0.0",
                type: "RULE",
                validate(ruleId, mr, config) {
                    return [{
                        severity: "ERROR",
                        message: "Test finding",
                        rule: ruleId
                    }];
                }
            };
            """.trimIndent()

            val bridge = JsModuleBridge.fromSource(engine, source, "test.mjs")
            val mr = createTestMergeRequest()

            val findings = (bridge as RuleModule).validate("test-rule", mr, JsonObject(emptyMap()))

            assertEquals(1, findings.size)
            assertEquals(Severity.ERROR, findings[0].severity)
            assertEquals("Test finding", findings[0].message)
            assertEquals("test-rule", findings[0].rule)
        }

    @Test
    fun testValidateFindingWithSuggestionField() =
        runTest {
            val source = """
            module.exports = {
                id: "test",
                name: "Test",
                version: "1.0.0",
                type: "RULE",
                validate(ruleId, mr, config) {
                    return [{
                        severity: "WARNING",
                        message: "Title too short",
                        rule: ruleId,
                        explanation: "Titles must be at least 10 characters",
                        suggestion: "Prefix your title with a scope, e.g. 'feat: '"
                    }];
                }
            };
            """.trimIndent()

            val bridge = JsModuleBridge.fromSource(engine, source, "test.mjs")
            val mr = createTestMergeRequest()

            val findings = (bridge as RuleModule).validate("test-rule", mr, JsonObject(emptyMap()))

            assertEquals(1, findings.size)
            assertEquals(Severity.WARNING, findings[0].severity)
            assertEquals("Titles must be at least 10 characters", findings[0].explanation)
            assertEquals("Prefix your title with a scope, e.g. 'feat: '", findings[0].suggestion)
        }

    @Test
    fun testValidateRuntimeErrorReturnsEmptyList() =
        runTest {
            val source = """
            module.exports = {
                id: "test",
                name: "Test",
                version: "1.0.0",
                type: "RULE",
                validate(ruleId, mr, config) {
                    throw new Error("Validation error");
                }
            };
            """.trimIndent()

            val bridge = JsModuleBridge.fromSource(engine, source, "test.mjs")
            val mr = createTestMergeRequest()

            val findings = (bridge as RuleModule).validate("test-rule", mr, JsonObject(emptyMap()))
            assertTrue(findings.isEmpty())
        }

    @Test
    fun testValidateReceivesMergeRequest() =
        runTest {
            val source = """
            module.exports = {
                id: "test",
                name: "Test",
                version: "1.0.0",
                type: "RULE",
                validate(ruleId, mr, config) {
                    if (mr.title === "Test MR" && mr.source_branch === "feature") {
                        return [{
                            severity: "INFO",
                            message: "Correct MR received",
                            rule: ruleId
                        }];
                    }
                    return [];
                }
            };
            """.trimIndent()

            val bridge = JsModuleBridge.fromSource(engine, source, "test.mjs")
            val mr = createTestMergeRequest(title = "Test MR", sourceBranch = "feature")

            val findings = (bridge as RuleModule).validate("test-rule", mr, JsonObject(emptyMap()))

            assertEquals(1, findings.size)
            assertEquals("Correct MR received", findings[0].message)
        }

    @Test
    fun testValidateReceivesConfig() =
        runTest {
            val source = """
            module.exports = {
                id: "test",
                name: "Test",
                version: "1.0.0",
                type: "RULE",
                validate(ruleId, mr, config) {
                    if (config.threshold === 10) {
                        return [{
                            severity: "INFO",
                            message: "Config received: " + config.threshold,
                            rule: ruleId
                        }];
                    }
                    return [];
                }
            };
            """.trimIndent()

            val bridge = JsModuleBridge.fromSource(engine, source, "test.mjs")
            val mr = createTestMergeRequest()
            val config = mapOf("threshold" to 10)

            val findings = (bridge as RuleModule).validate("test-rule", mr, mapToJsonElement(config))

            assertEquals(1, findings.size)
            assertEquals("Config received: 10", findings[0].message)
        }

    @Test
    fun testValidateWithSpecialCharactersInRuleId() =
        runTest {
            val source = """
            module.exports = {
                id: "test",
                name: "Test",
                version: "1.0.0",
                type: "RULE",
                validate(ruleId, mr, config) {
                    return [{
                        severity: "INFO",
                        message: "Rule: " + ruleId,
                        rule: ruleId
                    }];
                }
            };
            """.trimIndent()

            val bridge = JsModuleBridge.fromSource(engine, source, "test.mjs")
            val mr = createTestMergeRequest()

            // Test with special characters that need escaping
            val ruleId = "test-rule-with-'quotes'-and-\"double\"-quotes"
            val findings = (bridge as RuleModule).validate(ruleId, mr, JsonObject(emptyMap()))

            assertEquals(1, findings.size)
            assertTrue(findings[0].message.contains("quotes"))
        }

    // --- report tests ---

    @Test
    fun testReportWithoutFunctionReturnsNull() =
        runTest {
            val source = """
            module.exports = {
                id: "test",
                name: "Test",
                version: "1.0.0",
                type: "REPORTER",
                formatId: "json"
            };
            """.trimIndent()

            val bridge = JsModuleBridge.fromSource(engine, source, "test.mjs")

            val report = (bridge as ReporterModule).report(emptyList(), true, JsonObject(emptyMap()))
            assertNull(report)
        }

    @Test
    fun testReportReturnsString() =
        runTest {
            val source = """
            module.exports = {
                id: "test",
                name: "Test",
                version: "1.0.0",
                type: "REPORTER",
                formatId: "json",
                report(findings, passed, config) {
                    return "Test report";
                }
            };
            """.trimIndent()

            val bridge = JsModuleBridge.fromSource(engine, source, "test.mjs")

            val report = (bridge as ReporterModule).report(emptyList(), true, JsonObject(emptyMap()))
            assertEquals("Test report", report)
        }

    @Test
    fun testReportReceivesFindings() =
        runTest {
            val source = """
            module.exports = {
                id: "test",
                name: "Test",
                version: "1.0.0",
                type: "REPORTER",
                formatId: "json",
                report(findings, passed, config) {
                    return "Total findings: " + findings.length;
                }
            };
            """.trimIndent()

            val bridge = JsModuleBridge.fromSource(engine, source, "test.mjs")
            val findings = listOf(
                Finding(Severity.ERROR, "Error 1", "rule1"),
                Finding(Severity.WARNING, "Warning 1", "rule2")
            )

            val report = (bridge as ReporterModule).report(findings, false, JsonObject(emptyMap()))
            assertEquals("Total findings: 2", report)
        }

    @Test
    fun testReportReceivesPassedStatus() =
        runTest {
            val source = """
            module.exports = {
                id: "test",
                name: "Test",
                version: "1.0.0",
                type: "REPORTER",
                formatId: "json",
                report(findings, passed, config) {
                    return passed ? "PASSED" : "FAILED";
                }
            };
            """.trimIndent()

            val bridge = JsModuleBridge.fromSource(engine, source, "test.mjs")

            val passedReport = (bridge as ReporterModule).report(emptyList(), true, JsonObject(emptyMap()))
            assertEquals("PASSED", passedReport)

            val failedReport = bridge.report(emptyList(), false, JsonObject(emptyMap()))
            assertEquals("FAILED", failedReport)
        }

    @Test
    fun testReportReceivesConfig() =
        runTest {
            val source = """
            module.exports = {
                id: "test",
                name: "Test",
                version: "1.0.0",
                type: "REPORTER",
                formatId: "json",
                report(findings, passed, config) {
                    return "Format: " + config.format;
                }
            };
            """.trimIndent()

            val bridge = JsModuleBridge.fromSource(engine, source, "test.mjs")
            val config = mapOf("format" to "detailed")

            val report = (bridge as ReporterModule).report(emptyList(), true, mapToJsonElement(config))
            assertEquals("Format: detailed", report)
        }

    @Test
    fun testReportReturnsNull() =
        runTest {
            val source = """
            module.exports = {
                id: "test",
                name: "Test",
                version: "1.0.0",
                type: "REPORTER",
                formatId: "json",
                report(findings, passed, config) {
                    return null;
                }
            };
            """.trimIndent()

            val bridge = JsModuleBridge.fromSource(engine, source, "test.mjs")

            val report = (bridge as ReporterModule).report(emptyList(), true, JsonObject(emptyMap()))
            assertNull(report)
        }

    @Test
    fun testReportErrorReturnsNull() =
        runTest {
            val source = """
            module.exports = {
                id: "test",
                name: "Test",
                version: "1.0.0",
                type: "REPORTER",
                formatId: "json",
                report(findings, passed, config) {
                    throw new Error("Report error");
                }
            };
            """.trimIndent()

            val bridge = JsModuleBridge.fromSource(engine, source, "test.mjs")

            val report = (bridge as ReporterModule).report(emptyList(), true, JsonObject(emptyMap()))
            assertNull(report)
        }

    @Test
    fun testReportFormatId() =
        runTest {
            val source = """
            module.exports = {
                id: "test",
                name: "Test",
                version: "1.0.0",
                type: "REPORTER",
                formatId: "custom-format"
            };
            """.trimIndent()

            val bridge = JsModuleBridge.fromSource(engine, source, "test.mjs")

            assertEquals("custom-format", (bridge as ReporterModule).formatId)
        }

    @Test
    fun testReportFormatIdDefaultEmpty() =
        runTest {
            val source = """
            module.exports = {
                id: "test",
                name: "Test",
                version: "1.0.0",
                type: "REPORTER"
            };
            """.trimIndent()

            val bridge = JsModuleBridge.fromSource(engine, source, "test.mjs")

            assertEquals("", (bridge as ReporterModule).formatId)
        }

    // --- updateTitle tests ---

    @Test
    fun testUpdateTitleCallsJsFunction() =
        runTest {
            val source = """
            var capturedArgs = null;
            module.exports = {
                id: "test",
                name: "Test",
                version: "1.0.0",
                type: "PLATFORM_SERVICE",
                updateTitle(projectId, mrId, title) {
                    capturedArgs = { projectId, mrId, title };
                }
            };
            """.trimIndent()

            val bridge = JsModuleBridge.fromSource(engine, source, "test.mjs")
            bridge.updateTitle("proj-1", "42", "New Title")

            val projectId = engine.evaluate<String>("capturedArgs.projectId")
            val mrId = engine.evaluate<String>("capturedArgs.mrId")
            val title = engine.evaluate<String>("capturedArgs.title")
            assertEquals("proj-1", projectId)
            assertEquals("42", mrId)
            assertEquals("New Title", title)
        }

    @Test
    fun testUpdateTitleIsANoOpWhenMethodMissing() =
        runTest {
            val source = """
            module.exports = {
                id: "test",
                name: "Test",
                version: "1.0.0",
                type: "PLATFORM_SERVICE"
            };
            """.trimIndent()

            val bridge = JsModuleBridge.fromSource(engine, source, "test.mjs")
            bridge.updateTitle("proj", "1", "Title")
        }

    // --- updateDescription tests ---

    @Test
    fun testUpdateDescriptionCallsJsFunction() =
        runTest {
            val source = """
            var capturedArgs = null;
            module.exports = {
                id: "test",
                name: "Test",
                version: "1.0.0",
                type: "PLATFORM_SERVICE",
                updateDescription(projectId, mrId, description) {
                    capturedArgs = { projectId, mrId, description };
                }
            };
            """.trimIndent()

            val bridge = JsModuleBridge.fromSource(engine, source, "test.mjs")
            bridge.updateDescription("proj-1", "42", "New Description")

            val projectId = engine.evaluate<String>("capturedArgs.projectId")
            val mrId = engine.evaluate<String>("capturedArgs.mrId")
            val description = engine.evaluate<String>("capturedArgs.description")
            assertEquals("proj-1", projectId)
            assertEquals("42", mrId)
            assertEquals("New Description", description)
        }

    @Test
    fun testUpdateDescriptionIsANoOpWhenMethodMissing() =
        runTest {
            val source = """
            module.exports = {
                id: "test",
                name: "Test",
                version: "1.0.0",
                type: "PLATFORM_SERVICE"
            };
            """.trimIndent()

            val bridge = JsModuleBridge.fromSource(engine, source, "test.mjs")
            bridge.updateDescription("proj", "1", "Description")
        }

    // --- Helper methods ---

    private fun createTestContext() =
        object : PluginContext {
            override val pluginConfig: JsonElement = JsonObject(emptyMap())

            override fun log(message: String) {}

            override fun warn(message: String) {}

            override fun env(name: String): String? = null

            override val hostVersion: String = "0.1.0-test"
            override val workingDirectory: String = "."
        }
}
