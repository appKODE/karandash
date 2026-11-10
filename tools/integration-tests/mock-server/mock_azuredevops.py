"""Azure DevOps REST API (mounted at /ado) for the mock server.

Basic auth with the PAT `ado-pat` as the password and an empty user name (`Basic base64(":ado-pat")`);
anything else gets 401. Serves one pull request, org/proj/repo #1.
"""

import base64
import re

PREFIX = "/ado"
PAT = "ado-pat"
EXPECTED_AUTH = "Basic " + base64.b64encode((":" + PAT).encode()).decode()

USER = {"id": "u-1", "displayName": "Test User", "uniqueName": "test@example.com"}
SOURCE = "bbb222bbb222bbb222bbb222bbb222bbb222bbb2"
TARGET = "aaa111aaa111aaa111aaa111aaa111aaa111aaa1"

PULL = {
    "pullRequestId": 1,
    "title": "feat: add runtime verification for plugins",
    "description": "This PR adds Layer 2 runtime verification for all plugin types.",
    "status": "active",
    "createdBy": USER,
    "creationDate": "2026-01-15T10:00:00Z",
    "sourceRefName": "refs/heads/feature/runtime-verify",
    "targetRefName": "refs/heads/main",
    "isDraft": False,
    "labels": [{"id": "l-1", "name": "integration", "active": True}],
    "reviewers": [],
    "lastMergeSourceCommit": {"commitId": SOURCE},
    "lastMergeTargetCommit": {"commitId": TARGET},
    "mergeStatus": "succeeded",
}

AUTHOR = {"name": "Test User", "email": "test@example.com", "date": "2026-01-15T10:00:00Z"}
COMMIT = {"commitId": SOURCE, "comment": "feat: initial commit", "author": AUTHOR, "committer": AUTHOR}


def handle(req):
    if not req.path.startswith(PREFIX + "/"):
        return None
    if req.headers.get("Authorization") != EXPECTED_AUTH:
        return 401, {"message": "TF400813: The user is not authorized to access this resource."}
    path = req.path[len(PREFIX):]
    if re.fullmatch(r"/[^/]+/_apis/connectionData", path):
        return 200, {"authenticatedUser": USER}
    m = re.fullmatch(r"/[^/]+/[^/]+/_apis/git/repositories/[^/]+/pullrequests/1(/.*)?", path, re.IGNORECASE)
    if not m:
        return 404, {"message": "Not Found"}
    rest = m.group(1) or ""
    if req.method == "GET":
        if rest == "":
            return 200, PULL
        if rest == "/commits":
            return 200, {"value": [COMMIT], "count": 1}
        if rest == "/statuses":
            return 200, {"value": []}
        if rest == "/iterations":
            return 200, {"value": [{"id": 1}]}
        if rest == "/iterations/1/changes":
            return 200, {"changeEntries": [{"item": {"path": "/src/main.kt"}, "changeType": "edit"}], "nextSkip": 0}
        if rest == "/threads":
            return 200, {"value": [], "count": 0}
    if req.method == "POST" and rest == "/threads":
        return 200, {"id": 501, "threadId": 501, "comments": [{"id": 1, "content": "posted"}], "status": "active"}
    return 404, {"message": "Not Found"}
