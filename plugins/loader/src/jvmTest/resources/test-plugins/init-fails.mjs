export default {
    id: "init-fails-plugin",
    name: "Init Fails Plugin",
    version: "1.0.0",
    type: "RULE",

    initialize(context) {
        return false;  // Intentional failure
    }
};
