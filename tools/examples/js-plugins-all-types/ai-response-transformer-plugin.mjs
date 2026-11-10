export default {
  id: "js-ai-response-transformer-plugin",
  name: "JS AI Response Transformer Plugin",
  version: "1.0.0",
  type: "AI_RESPONSE_TRANSFORMER",
  transformerPriority: 100,

  initialize(context) { return true; },
  shutdown() {},

  transformResponse(responseJson, requestJson, contextJson) {
    return typeof responseJson === 'string' ? JSON.parse(responseJson) : responseJson;
  }
};
