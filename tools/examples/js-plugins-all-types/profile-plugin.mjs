export default {
  id: "js-profile-plugin",
  name: "JS Profile Plugin",
  version: "1.0.0",
  type: "PROFILE",

  initialize(context) { return true; },
  shutdown() {},

  profileId: "js-example-profile",
  summary: "Example JS profile for testing",
  tags: ["javascript", "example"],
  ruleConfigs() {
    return { titleFormat: { pattern: "^.*$" } };
  },
  reporterConfigs() { return {}; }
};
