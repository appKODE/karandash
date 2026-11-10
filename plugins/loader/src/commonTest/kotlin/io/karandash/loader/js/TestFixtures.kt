package io.karandash.loader.js

object TestFixtures {
    val VALID_RULE_PLUGIN = """
        export default {
            id: "test-rule-plugin",
            name: "Test Rule Plugin",
            version: "1.0.0",
            type: "RULE",

            initialize(context) {
                context.log("Test rule plugin initialized");
                return true;
            },

            contributedRules() {
                return ["test-title-length", "test-description-present"];
            },

            validate(ruleId, mergeRequest, config) {
                const findings = [];

                if (ruleId === "test-title-length") {
                    if (mergeRequest.title.length < 10) {
                        findings.push({
                            severity: "WARNING",
                            message: "MR title too short (minimum 10 characters)",
                            rule: ruleId
                        });
                    }
                }

                if (ruleId === "test-description-present") {
                    if (!mergeRequest.description || mergeRequest.description.trim() === "") {
                        findings.push({
                            severity: "ERROR",
                            message: "MR must have a description",
                            rule: ruleId
                        });
                    }
                }

                return findings;
            },

            shutdown() {
                // Cleanup
            }
        };
    """.trimIndent()

    val VALID_REPORTER_PLUGIN = """
        export default {
            id: "test-reporter-plugin",
            name: "Test Reporter Plugin",
            version: "1.0.0",
            type: "REPORTER",
            formatId: "json",

            initialize(context) {
                return true;
            },

            report(findings, passed, config) {
                return JSON.stringify({
                    passed: passed,
                    totalFindings: findings.length,
                    findings: findings
                });
            },

            shutdown() {}
        };
    """.trimIndent()

    val INVALID_SYNTAX = """
        function { broken syntax here
        export default { incomplete
    """.trimIndent()

    val MISSING_FIELDS = """
        export default {
            // Missing: id
            name: "Incomplete Plugin",
            version: "1.0.0",
            type: "RULE"
        };
    """.trimIndent()

    val INIT_FAILS = """
        export default {
            id: "init-fails-plugin",
            name: "Init Fails Plugin",
            version: "1.0.0",
            type: "RULE",

            initialize(context) {
                return false;  // Intentional failure
            }
        };
    """.trimIndent()

    val DUPLICATE_PLUGIN = VALID_RULE_PLUGIN // Same as valid rule
}
