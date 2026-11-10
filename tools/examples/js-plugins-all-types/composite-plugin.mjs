// One guest, several module types: describe() lists every type it serves and the host registers
// one part per type under the same id. `type` is the primary (first) one.
export default {
  id: "js-composite-plugin",
  name: "JS Composite Plugin",
  version: "1.0.0",
  type: "RULE",

  describe() {
    return {
      id: "js-composite-plugin",
      types: ["RULE", "REPORTER", "PROFILE", "CLI_COMMAND", "CONFIG_FORMAT"],
      properties: {}
    };
  },

  initialize(context) { return true; },
  shutdown() {},

  // RULE
  contributedRules() { return ["js-composite-title"]; },
  validate(ruleId, mergeRequest, config) {
    if (ruleId !== "js-composite-title" || mergeRequest.title.length > 0) return [];
    return [{ rule: ruleId, message: "Title is empty", severity: "ERROR", location: null }];
  },

  // REPORTER
  formatId: "js-composite-report",
  report(findings, passed, config) {
    return `JS-COMPOSITE-REPORT passed=${passed} findings=${findings.length}`;
  },

  // PROFILE
  profileId: "js-composite-profile",
  summary: "Profile served by the composite example",
  tags: ["javascript", "composite"],
  ruleConfigs() { return { "js-composite-title": { enabled: true } }; },
  reporterConfigs() { return {}; },

  // CLI_COMMAND
  cliCommands() {
    return [{ name: "js-composite", help: "Command served by the composite example", subcommands: [] }];
  },

  // Runs a listed command: `command` is its path (names joined by a space), `args` the remaining arguments.
  runCliCommand(command, args) {
    return { exitCode: 0, stdout: [`JS-COMPOSITE-RAN ${command} args=${args.join(" ")}`], stderr: [] };
  },

  // CONFIG_FORMAT: *.jscomp files hold plain JSON
  configFormatId: "js-composite-config",
  fileExtensions: ["jscomp"],
  parseToMap(content) { return JSON.parse(content); },
  parseExtendsOnly(content) { return null; }
};
