"""Bitbucket Cloud REST API 2.0 (mounted at /bb/2.0) for the mock server.

Accepts HTTP Basic `alice:app-pass` (app password) or `Authorization: Bearer bb-token` (access token); any other
credential gets 401.
"""

import base64
import re

PREFIX = "/bb/2.0"
BASIC = "Basic " + base64.b64encode(b"alice:app-pass").decode()
BEARER = "Bearer bb-token"

USER = {"uuid": "{u-1}", "username": "alice", "display_name": "Alice", "nickname": "alice"}
REPO = {"uuid": "{r-1}", "name": "repo", "full_name": "ws/repo"}
HEAD_SHA = "bbb222bbb222bbb222bbb222bbb222bbb222bbb2"
BASE_SHA = "aaa111aaa111aaa111aaa111aaa111aaa111aaa1"

PULL = {
    "id": 1,
    "title": "feat: add runtime verification for plugins",
    "description": "This PR adds Layer 2 runtime verification for all plugin types.",
    "state": "OPEN",
    "author": USER,
    "source": {"branch": {"name": "feature/runtime-verify"}, "commit": {"hash": HEAD_SHA}, "repository": REPO},
    "destination": {"branch": {"name": "main"}, "commit": {"hash": BASE_SHA}, "repository": REPO},
    "created_on": "2026-01-15T10:00:00+00:00",
    "updated_on": "2026-01-15T11:00:00+00:00",
    "participants": [],
    "reviewers": [],
}

COMMITS = {
    "values": [
        {
            "hash": "abc123def456abc123def456abc123def456abc1",
            "date": "2026-01-15T10:00:00+00:00",
            "message": "feat: initial commit",
            "author": {"raw": "Alice <alice@example.com>", "user": USER},
        }
    ]
}

DIFFSTAT = {
    "values": [
        {
            "status": "modified",
            "old": {"path": "src/main.kt", "type": "commit_file"},
            "new": {"path": "src/main.kt", "type": "commit_file"},
            "lines_added": 2,
            "lines_removed": 0,
        }
    ]
}

STATUSES = {"values": [{"key": "build", "state": "SUCCESSFUL", "name": "build"}]}

DIFF = (
    "diff --git a/src/main.kt b/src/main.kt\n--- a/src/main.kt\n+++ b/src/main.kt\n"
    "@@ -1,3 +1,5 @@\n fun main() {\n+    println(\"runtime verify\")\n+    verify()\n     println(\"hello\")\n }\n"
)

COMMENT = {"id": 401, "content": {"raw": "Karandash validation posted"}, "user": USER, "created_on": "2026-01-15T12:00:00+00:00"}


def handle(req):
    if not req.path.startswith(PREFIX + "/"):
        return None
    if req.headers.get("Authorization") not in (BASIC, BEARER):
        return 401, {"type": "error", "error": {"message": "Unauthorized"}}
    path = req.path[len(PREFIX):]
    if path == "/user":
        return 200, USER
    m = re.fullmatch(r"/repositories/[^/]+/[^/]+/pullrequests/\d+(/[a-z]+)?", path)
    if not m:
        return 404, {"type": "error", "error": {"message": "not found"}}
    rest = m.group(1) or ""
    if req.method == "GET":
        if rest == "/diff":
            return 200, DIFF, {"Content-Type": "text/plain"}
        return 200, {"": PULL, "/commits": COMMITS, "/diffstat": DIFFSTAT, "/statuses": STATUSES}.get(rest, {"values": []})
    if req.method == "POST" and rest == "/comments":
        return 201, COMMENT
    return 404, {"type": "error", "error": {"message": "not found"}}
