"""Gitea REST API (mounted at /gitea, API under /gitea/api/v1) for the mock server.

Accepts the access token `gitea-token` sent as `Authorization: token gitea-token`; any other credential gets 401.
"""

import re

PREFIX = "/gitea"
TOKEN = "gitea-token"

USER = {"id": 1, "login": "testuser", "full_name": "Test User", "email": "test@example.com"}
HEAD_SHA = "bbb222bbb222bbb222bbb222bbb222bbb222bbb2"
BASE_SHA = "aaa111aaa111aaa111aaa111aaa111aaa111aaa1"
REPO = {"id": 5, "name": "repo", "full_name": "owner/repo", "owner": USER, "html_url": "https://gitea.example/owner/repo"}

PULL = {
    "id": 200,
    "number": 1,
    "title": "feat: add runtime verification for plugins",
    "body": "This PR adds Layer 2 runtime verification for all plugin types.",
    "html_url": "https://gitea.example/owner/repo/pulls/1",
    "state": "open",
    "user": USER,
    "head": {"ref": "feature/runtime-verify", "sha": HEAD_SHA, "repo": REPO},
    "base": {"ref": "main", "sha": BASE_SHA, "repo": REPO},
    "labels": [{"id": 1, "name": "integration"}],
    "draft": False,
    "mergeable": True,
    "merged": False,
    "created_at": "2026-01-15T10:00:00Z",
    "updated_at": "2026-01-15T11:00:00Z",
    "requested_reviewers": [],
}

COMMITS = [
    {
        "sha": "abc123def456abc123def456abc123def456abc1",
        "commit": {
            "message": "feat: initial commit",
            "author": {"name": "Test User", "email": "test@example.com", "date": "2026-01-15T10:00:00Z"},
            "committer": {"name": "Test User", "email": "test@example.com", "date": "2026-01-15T10:00:00Z"},
        },
    }
]

FILES = [{"filename": "src/main.kt", "status": "modified", "additions": 2, "deletions": 0, "changes": 2}]

DIFF = (
    "diff --git a/src/main.kt b/src/main.kt\n--- a/src/main.kt\n+++ b/src/main.kt\n"
    "@@ -1,3 +1,5 @@\n fun main() {\n+    println(\"runtime verify\")\n+    verify()\n     println(\"hello\")\n }\n"
)

COMMENT = {"id": 301, "body": "Karandash validation posted", "user": USER, "created_at": "2026-01-15T12:00:00Z"}


def handle(req):
    if not req.path.startswith(PREFIX + "/"):
        return None
    if req.headers.get("Authorization") != f"token {TOKEN}":
        return 401, {"message": "user does not exist [uid: 0, name: ]", "url": "https://gitea.example/api/swagger"}
    path = req.path[len(PREFIX):]
    if path == "/api/v1/user":
        return 200, USER
    m = re.fullmatch(r"/api/v1/repos/[^/]+/[^/]+/(pulls|issues|commits)/([^/]+?)(\.diff|/.*)?", path)
    if not m:
        return 404, {"message": "not found"}
    kind, rest = m.group(1), m.group(3) or ""
    if req.method == "GET":
        if kind == "commits":
            return 200, {"state": "success", "statuses": [{"context": "ci/build", "status": "success"}]}
        if kind == "issues":
            return 200, []
        if rest == ".diff":
            return 200, DIFF, {"Content-Type": "text/plain"}
        return 200, {"": PULL, "/commits": COMMITS, "/files": FILES, "/reviews": []}.get(rest, [])
    if req.method == "POST" and kind == "issues" and rest == "/comments":
        return 201, COMMENT
    return 404, {"message": "not found"}
