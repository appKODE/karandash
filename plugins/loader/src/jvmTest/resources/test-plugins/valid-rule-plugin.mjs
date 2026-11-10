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
