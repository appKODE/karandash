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
