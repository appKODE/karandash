export default {
    id: "validate-throws-plugin",
    name: "Validate Throws Plugin",
    version: "1.0.0",
    type: "RULE",

    initialize(context) {
        return true;
    },

    contributedRules() {
        return ["throwing-rule"];
    },

    validate(ruleId, mergeRequest, config) {
        throw new Error("Validation error");
    }
};
