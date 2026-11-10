// Extensibility probe - JS guest.
//
// An AI-analyzer-ONLY guest. The host must:
//   - forward the enrichment declarations below (today JsModuleBridge stubs them to emptyList()),
//   - forward needsScopeDetection (today not overridden by the bridge, so the SDK default `false` wins),
//   - NOT expose this guest as a RuleModule (today JsModuleBridge implements every SDK interface).
//
// Checked by plugins/integration-tests/contracts/.../probe/ExtensibilityProbeGuestContractTest.kt.
export default {
  id: "extensibility-probe-js",
  name: "Extensibility Probe (JS)",
  version: "0.0.0",
  type: "AI_ANALYZER",

  initialize(context) { return true; },
  shutdown() {},

  analyzerId: "extensibility-probe-js",
  defaultPrompt: "Probe analyzer: report nothing.",
  enabledByDefault: false,

  supportedEnrichment: ["probe.ticket", "probe.owners"],
  requiredEnrichment: ["probe.ticket"],
  needsScopeDetection: true,

  analyze(mrJson, budgetJson, model, configJson) {
    return [];
  }
};
