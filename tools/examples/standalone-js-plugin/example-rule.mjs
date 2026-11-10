/**
 * Standalone JavaScript Rule Plugin for Karandash
 *
 * This is a complete, self-contained example of a Karandash rule plugin
 * written in JavaScript. It demonstrates:
 * - ES Module format
 * - Plugin interface implementation
 * - Configuration handling
 * - MR validation logic
 *
 * Installation: cp example-rule.mjs ~/.karandash/plugins/
 */

export default {
  // Required: Module identity
  id: "example-js-plugin",
  name: "Example JS Rule Plugin",
  version: "1.0.0",
  type: "RULE",

  /**
   * Initialize the plugin
   * @param {Object} context - Plugin context with host information
   * @returns {boolean} - true if initialization successful
   */
  initialize(context) {
    console.log(`Example JS Plugin initialized (Karandash ${context.hostVersion})`);
    return true;
  },

  /**
   * Cleanup on shutdown
   */
  shutdown() {
    console.log("Example JS Plugin shutting down");
  },

  /**
   * Declare which rules this plugin provides
   * @returns {string[]} - Array of rule IDs
   */
  contributedRules() {
    return ["js-title-length", "js-description-required"];
  },

  /**
   * Validate a merge request
   * @param {string} ruleId - The rule ID to validate
   * @param {Object} mergeRequest - The merge request data
   * @param {Object} config - Rule configuration
   * @returns {Object[]} - Array of findings
   */
  validate(ruleId, mergeRequest, config) {
    switch (ruleId) {
      case "js-title-length":
        return validateTitleLength(mergeRequest, config);
      case "js-description-required":
        return validateDescriptionRequired(mergeRequest, config);
      default:
        return [];
    }
  }
};

/**
 * Validates that MR title length is within configured bounds.
 *
 * Configuration:
 * - minLength: number (default: 10)
 * - maxLength: number (default: 100)
 */
function validateTitleLength(mr, config) {
  const minLength = config.minLength || 10;
  const maxLength = config.maxLength || 100;
  const findings = [];

  if (mr.title.length < minLength) {
    findings.push({
      rule: "js-title-length",
      message: `Title too short (${mr.title.length} chars). Minimum: ${minLength}`,
      severity: "ERROR",
      location: null
    });
  }

  if (mr.title.length > maxLength) {
    findings.push({
      rule: "js-title-length",
      message: `Title too long (${mr.title.length} chars). Maximum: ${maxLength}`,
      severity: "ERROR",
      location: null
    });
  }

  return findings;
}

/**
 * Validates that MR has a non-empty description.
 *
 * Configuration:
 * - minLength: number (default: 20)
 * - allowEmpty: boolean (default: false)
 */
function validateDescriptionRequired(mr, config) {
  const minLength = config.minLength || 20;
  const allowEmpty = config.allowEmpty || false;

  if (!mr.description || mr.description.trim().length === 0) {
    if (!allowEmpty) {
      return [{
        rule: "js-description-required",
        message: "MR description is required",
        severity: "ERROR",
        location: null
      }];
    }
    return [];
  }

  if (mr.description.trim().length < minLength) {
    return [{
      rule: "js-description-required",
      message: `Description too short (${mr.description.trim().length} chars). Minimum: ${minLength}`,
      severity: "WARNING",
      location: null
    }];
  }

  return [];
}
