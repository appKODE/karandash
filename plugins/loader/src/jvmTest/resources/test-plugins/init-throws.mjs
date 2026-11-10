export default {
    id: "init-throws-plugin",
    name: "Init Throws Plugin",
    version: "1.0.0",
    type: "RULE",

    initialize(context) {
        throw new Error("Initialization failed");
    }
};
