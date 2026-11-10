export default {
  id: "js-ai-interceptor-plugin",
  name: "JS AI Interceptor Plugin",
  version: "1.0.0",
  type: "AI_INTERCEPTOR",
  interceptorPriority: 100,

  initialize(context) { return true; },
  shutdown() {},

  intercept(requestJson, contextJson) {
    return { passthrough: true };
  }
};
