const mutationLog = [];
const mrState = {};

export default {
  id: "js-mutator-plugin",
  name: "JS Mock MR Mutator Plugin",
  version: "1.0.0",
  type: "MERGE_REQUEST_MUTATOR",
  isDefaultEnabled: false,

  initialize(context) {
    context.log(`${this.name} initialized (Karandash ${context.hostVersion})`);
    mutationLog.length = 0;
    return true;
  },

  shutdown() {
    mutationLog.length = 0;
  },

  defaultConfig() {
    return {
      dryRun: false
    };
  },

  addLabels(projectId, mrId, labels) {
    const key = mrKey(projectId, mrId);
    if (!mrState[key]) mrState[key] = { labels: [], draft: false };
    for (const label of labels) {
      if (!mrState[key].labels.includes(label)) {
        mrState[key].labels.push(label);
      }
    }
    mutationLog.push({ action: "addLabels", projectId, mrId, labels, timestamp: now() });
  },

  removeLabels(projectId, mrId, labels) {
    const key = mrKey(projectId, mrId);
    if (!mrState[key]) mrState[key] = { labels: [], draft: false };
    mrState[key].labels = mrState[key].labels.filter(l => !labels.includes(l));
    mutationLog.push({ action: "removeLabels", projectId, mrId, labels, timestamp: now() });
  },

  setDraft(projectId, mrId, draft) {
    const key = mrKey(projectId, mrId);
    if (!mrState[key]) mrState[key] = { labels: [], draft: false };
    mrState[key].draft = draft;
    mutationLog.push({ action: "setDraft", projectId, mrId, draft, timestamp: now() });
  },

  updateTitle(projectId, mrId, title) {
    const key = mrKey(projectId, mrId);
    if (!mrState[key]) mrState[key] = { labels: [], draft: false };
    mrState[key].title = title;
    mutationLog.push({ action: "updateTitle", projectId, mrId, title, timestamp: now() });
  },

  updateDescription(projectId, mrId, description) {
    const key = mrKey(projectId, mrId);
    if (!mrState[key]) mrState[key] = { labels: [], draft: false };
    mrState[key].description = description;
    mutationLog.push({ action: "updateDescription", projectId, mrId, description, timestamp: now() });
  },

  getMutationLog() {
    return [...mutationLog];
  },

  getMRState(projectId, mrId) {
    return mrState[mrKey(projectId, mrId)] || { labels: [], draft: false };
  },

  clearLog() {
    mutationLog.length = 0;
    Object.keys(mrState).forEach(k => delete mrState[k]);
  }
};

function mrKey(projectId, mrId) {
  return `${projectId}::${mrId}`;
}

function now() {
  return new Date().toISOString();
}
