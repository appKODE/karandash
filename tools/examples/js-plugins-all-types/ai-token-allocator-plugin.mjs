export default {
  id: "js-ai-token-allocator-plugin",
  name: "JS AI Token Allocator Plugin",
  version: "1.0.0",
  type: "AI_TOKEN_ALLOCATOR",

  initialize(context) { return true; },
  shutdown() {},

  allocate(rulesJson, totalBudget, prioritiesJson) {
    return {};
  }
};
