export default {
  id: "js-provider-plugin",
  name: "JS Mock MR Provider Plugin",
  version: "1.0.0",
  type: "MERGE_REQUEST_PROVIDER",
  isDefaultEnabled: false,

  initialize(context) {
    context.log(`${this.name} initialized (Karandash ${context.hostVersion})`);
    return true;
  },

  shutdown() {
    // no resources to release
  },

  defaultConfig() {
    return {
      projectId: "mock-project"
    };
  },

  missingAuthMessage() {
    return "js-provider-plugin is a mock provider and does not require authentication. " +
      "Set 'providers.js-mock.projectId' in your config to customize the project ID.";
  },

  fetchMergeRequest(projectId, mrId) {
    return buildMockMR(projectId, mrId);
  },

  fetchMergeRequestByUrl(url) {
    const match = url.match(/\/(\d+)\/?$/);
    const mrId = match ? match[1] : "1";
    const projectId = extractProjectId(url);
    return buildMockMR(projectId, mrId);
  }
};

function buildMockMR(projectId, mrId) {
  return {
    id: String(mrId),
    iid: parseInt(mrId, 10) || 1,
    title: "feat(core): add new validation rule for branch naming",
    description: "This MR introduces a branch naming validation rule.\n\n" +
      "## Changes\n- Added `BranchNamingValidator`\n- Updated config schema\n- Added tests\n\n" +
      "## Testing\nAll existing tests pass. New tests cover happy path and edge cases.",
    source_branch: "feature/branch-naming-rule",
    target_branch: "main",
    author: {
      username: "dev",
      name: "Developer",
      email: "dev@example.com"
    },
    state: "opened",
    draft: false,
    labels: ["enhancement", "validated"],
    commits: [
      {
        sha: "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2",
        message: "feat(core): add BranchNamingValidator",
        author: { name: "Developer", email: "dev@example.com" },
        timestamp: "2024-03-15T10:00:00Z"
      },
      {
        sha: "b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3",
        message: "test(core): add BranchNamingValidator tests",
        author: { name: "Developer", email: "dev@example.com" },
        timestamp: "2024-03-15T11:30:00Z"
      }
    ],
    changes: [
      { path: "core/src/BranchNamingValidator.kt", changeType: "ADDED", additions: 85, deletions: 0 },
      { path: "core/src/test/BranchNamingValidatorTest.kt", changeType: "ADDED", additions: 120, deletions: 0 },
      { path: "core/src/Config.kt", changeType: "MODIFIED", additions: 12, deletions: 2 }
    ],
    approvals: [],
    reviewers: [
      { username: "reviewer1", name: "Reviewer One", email: "reviewer1@example.com" }
    ],
    _mockProjectId: projectId
  };
}

function extractProjectId(url) {
  try {
    const u = new URL(url);
    const parts = u.pathname.replace(/\/merge_requests\/\d+\/?$/, "").replace(/^\//, "");
    return parts || "mock-project";
  } catch {
    return "mock-project";
  }
}
