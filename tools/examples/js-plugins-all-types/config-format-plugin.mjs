export default {
  id: "js-config-format-plugin",
  name: "JS Config Format Plugin",
  version: "1.0.0",
  type: "CONFIG_FORMAT",
  formatId: "js-example-format",
  fileExtensions: ["jsexf"],

  initialize(context) { return true; },
  shutdown() {},

  // JS-side contract: return a plain object. The JsModuleBridge serializes it
  // to JSON and deserializes it as a JsonElement on the Kotlin side.
  parseToMap(content) {
    return { parsed: true, source: "js-config-format" };
  },

  parseExtendsOnly(content) {
    return null;
  }
};
