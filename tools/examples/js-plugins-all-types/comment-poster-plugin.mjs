let nextId = 1;
const commentStore = {};

export default {
  id: "js-comment-poster-plugin",
  name: "JS Mock Comment Poster Plugin",
  version: "1.0.0",
  type: "COMMENT_POSTER",
  isDefaultEnabled: false,

  initialize(context) {
    context.log(`${this.name} initialized (Karandash ${context.hostVersion})`);
    nextId = 1;
    Object.keys(commentStore).forEach(k => delete commentStore[k]);
    return true;
  },

  shutdown() {
    Object.keys(commentStore).forEach(k => delete commentStore[k]);
    nextId = 1;
  },

  defaultConfig() {
    return {
      markerPrefix: "<!-- karandash:"
    };
  },

  postComment(projectId, mrId, body) {
    const id = String(nextId++);
    const entry = { id, projectId, mrId, body, type: "general", createdAt: now() };
    store(projectId, mrId, entry);
    return id;
  },

  updateComment(projectId, mrId, commentId, body) {
    const key = mrKey(projectId, mrId);
    if (!commentStore[key]) return;
    const entry = commentStore[key].find(c => c.id === commentId);
    if (entry) {
      entry.body = body;
      entry.updatedAt = now();
    }
  },

  findExistingComment(projectId, mrId, marker) {
    const key = mrKey(projectId, mrId);
    const entries = commentStore[key] || [];
    const found = entries.find(c => c.body.includes(marker));
    return found ? found.id : null;
  },

  postInlineComment(projectId, mrId, body, filePath, line, diffRefs) {
    const id = String(nextId++);
    const entry = {
      id,
      projectId,
      mrId,
      body,
      type: "inline",
      filePath,
      line,
      diffRefs,
      createdAt: now()
    };
    store(projectId, mrId, entry);
    return id;
  },

  getStoredComments(projectId, mrId) {
    return [...(commentStore[mrKey(projectId, mrId)] || [])];
  },

  clearStore() {
    Object.keys(commentStore).forEach(k => delete commentStore[k]);
    nextId = 1;
  }
};

function mrKey(projectId, mrId) {
  return `${projectId}::${mrId}`;
}

function store(projectId, mrId, entry) {
  const key = mrKey(projectId, mrId);
  if (!commentStore[key]) commentStore[key] = [];
  commentStore[key].push(entry);
}

function now() {
  return new Date().toISOString();
}
