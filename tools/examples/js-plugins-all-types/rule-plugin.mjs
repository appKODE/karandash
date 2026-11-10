export default {
  id: "js-rule-plugin",
  name: "JS Rule Plugin",
  version: "1.0.0",
  type: "RULE",
  defaultPriority: 100,
  isDefaultEnabled: false,

  initialize(context) {
    context.log(`${this.name} initialized (Karandash ${context.hostVersion})`);
    return true;
  },

  shutdown() {
    // no resources to release
  },

  contributedRules() {
    return ["js-title-format", "js-description-check"];
  },

  defaultConfig() {
    return {
      "js-title-format": {
        pattern: "^(feat|fix|chore|docs|refactor|test|style|perf)(\\(.+\\))?: .+",
        minLength: 10,
        maxLength: 100
      },
      "js-description-check": {
        minLength: 20,
        required: true
      }
    };
  },

  validate(ruleId, mergeRequest, config) {
    switch (ruleId) {
      case "js-title-format":
        return validateTitleFormat(mergeRequest, config);
      case "js-description-check":
        return validateDescriptionCheck(mergeRequest, config);
      default:
        return [];
    }
  }
};

function validateTitleFormat(mr, config) {
  const findings = [];
  const title = mr.title || "";
  const minLength = config.minLength || 10;
  const maxLength = config.maxLength || 100;

  if (title.length < minLength) {
    findings.push({
      severity: "ERROR",
      message: `Title too short (${title.length} chars, minimum ${minLength})`,
      rule: "js-title-format",
      location: null
    });
  }

  if (title.length > maxLength) {
    findings.push({
      severity: "ERROR",
      message: `Title too long (${title.length} chars, maximum ${maxLength})`,
      rule: "js-title-format",
      location: null
    });
  }

  if (config.pattern) {
    const regex = new RegExp(config.pattern);
    if (!regex.test(title)) {
      findings.push({
        severity: "ERROR",
        message: `Title does not match required pattern: ${config.pattern}`,
        rule: "js-title-format",
        location: null
      });
    }
  }

  return findings;
}

function validateDescriptionCheck(mr, config) {
  const required = config.required !== false;
  const minLength = config.minLength || 20;
  const description = (mr.description || "").trim();

  if (description.length === 0) {
    if (required) {
      return [{
        severity: "ERROR",
        message: "MR description is required but missing",
        rule: "js-description-check",
        location: null
      }];
    }
    return [];
  }

  if (description.length < minLength) {
    return [{
      severity: "WARNING",
      message: `Description too short (${description.length} chars, minimum ${minLength})`,
      rule: "js-description-check",
      location: null
    }];
  }

  return [];
}
