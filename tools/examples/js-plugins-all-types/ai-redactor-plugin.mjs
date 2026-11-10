export default {
  id: "js-ai-redactor-plugin",
  name: "JS AI Redactor Plugin",
  version: "1.0.0",
  type: "AI_REDACTOR",
  redactorPriority: 100,

  initialize(context) { return true; },
  shutdown() {},

  redact(text, contextJson) {
    return text;
  }
};
