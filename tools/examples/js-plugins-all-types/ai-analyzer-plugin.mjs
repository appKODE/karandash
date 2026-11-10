/// <reference path="../karandash-plugin.d.ts" />

/** @type {KarandashPluginContext | null} */
let ctx = null;

/** @type {KarandashAiAnalyzerPlugin} */
export default {
  id: "js-ai-analyzer-plugin",
  name: "JS AI Analyzer Plugin",
  version: "1.0.0",
  type: "AI_ANALYZER",

  initialize(context) { ctx = context; return true; },
  shutdown() { ctx = null; },

  analyzerId: "js-example-analyzer",
  defaultPrompt: "Analyze this merge request for issues.",
  enabledByDefault: false,

  analyze(mr, budget, model, config) {
    if (!ctx || !config.aiServiceAvailable) return [];
    const settings = (config.rules && config.rules["js-example-analyzer"]) || {};

    // Optional team guidelines from config (`guidelines: { file: "review.md" }`), cached per source branch.
    let guidelines = ctx.cache.get("js-example", mr.sourceBranch);
    if (guidelines === null && settings.guidelines) {
      const resolved = ctx.content.resolve(settings.guidelines);
      if ("err" in resolved) ctx.log.warn(`guidelines unavailable: ${resolved.err}`);
      else ctx.cache.put("js-example", mr.sourceBranch, (guidelines = resolved.content));
    }

    // The host adds the shared MR context blocks and charges this call's budget.
    const reply = ctx.ai.complete({
      prompt: [config.resolvedPrompt || this.defaultPrompt, guidelines, config.instructions, `Title: ${mr.title}`]
        .filter(Boolean)
        .join("\n\n"),
      maxTokens: 500,
    });
    if ("err" in reply) {
      ctx.log.warn(`ai.complete failed: ${reply.err}`);
      return [];
    }
    if (reply.content.trim() === "" || reply.content.trim() === "OK") return [];
    return [{ severity: "INFO", message: reply.content.trim(), rule: this.analyzerId, location: null }];
  },
};
