export default {
  id: "js-ai-provider-plugin",
  name: "JS AI Provider Plugin",
  version: "1.0.0",
  type: "AI_PROVIDER",

  initialize(context) { return true; },
  shutdown() {},

  providerId() { return "js-example"; },

  complete(requestJson) {
    const request = typeof requestJson === 'string' ? JSON.parse(requestJson) : requestJson;
    return {
      content: "Example JS AI response for: " + (request.prompt || "").substring(0, 50),
      promptTokens: 10,
      completionTokens: 20,
      totalTokens: 30,
      model: request.model || "js-example-model"
    };
  }
};
