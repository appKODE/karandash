export default {
  id: "js-ai-prompt-processor-plugin",
  name: "JS AI Prompt Processor Plugin",
  version: "1.0.0",
  type: "AI_PROMPT_PROCESSOR",
  processorPriority: 100,

  initialize(context) { return true; },
  shutdown() {},

  processPrompt(prompt, contextJson) {
    return prompt;
  },

  processSystemPrompt(systemPrompt, contextJson) {
    return systemPrompt;
  }
};
