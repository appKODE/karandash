export default {
  id: 'js-script-example',
  name: 'JavaScript Script Plugin Example',
  version: '1.0.0',
  type: 'SCRIPT',

  initialize(context) {
    context.log(`${this.name} initialized (Karandash ${context.hostVersion})`);
    return true;
  },

  shutdown() {
    // no resources to release
  },

  executeScripts(scriptsJson, mrJson, contextJson) {
    return JSON.stringify([{
      rule: 'js-script',
      message: 'Script executed successfully',
      severity: 'INFO',
      location: null
    }]);
  }
};
