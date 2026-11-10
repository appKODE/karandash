export default {
  id: "js-ai-context-enricher-plugin",
  name: "JS AI Context Enricher Plugin",
  version: "1.0.0",
  type: "AI_CONTEXT_ENRICHER",
  enricherPriority: 100,

  initialize(context) { return true; },
  shutdown() {},

  enrichContext(mrJson, contextJson) {
    return { js_enriched: "true", source: "js-plugin" };
  }
};
