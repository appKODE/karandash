"""Gerrit REST API (mounted at /gerrit) for the mock server.

Authenticated calls live under /gerrit/a/ and need HTTP Basic `alice:http-pass`; anything else gets 401.
Every JSON answer carries Gerrit's `)]}'` anti-XSSI prefix.
"""

import base64
import json

PREFIX = "/gerrit"
CREDENTIALS = "alice:http-pass"
XSSI = ")]}'\n"

ACCOUNT = {"_account_id": 1000001, "name": "Alice", "email": "alice@example.com", "username": "alice"}
REV = "abc123def456abc123def456abc123def456abc1"

CHANGE = {
    "id": "proj~main~I8473b95934b5732ac55d26311a706c9c2bde9940",
    "project": "proj",
    "branch": "main",
    "change_id": "I8473b95934b5732ac55d26311a706c9c2bde9940",
    "subject": "feat: add runtime verification for plugins",
    "status": "NEW",
    "_number": 7,
    "owner": ACCOUNT,
    "work_in_progress": False,
    "mergeable": True,
    "current_revision": REV,
    "revisions": {
        REV: {
            "kind": "REWORK",
            "_number": 1,
            "created": "2026-01-15 10:00:00.000000000",
            "uploader": ACCOUNT,
            "ref": "refs/changes/07/7/1",
            "commit": {
                "commit": REV,
                "parents": [{"commit": "aaa111aaa111aaa111aaa111aaa111aaa111aaa1", "subject": "base"}],
                "author": {"name": "Alice", "email": "alice@example.com", "date": "2026-01-15 10:00:00.000000000"},
                "committer": {"name": "Alice", "email": "alice@example.com", "date": "2026-01-15 10:00:00.000000000"},
                "subject": "feat: add runtime verification for plugins",
                "message": "feat: add runtime verification for plugins\n\nAdds Layer 2 verification.\n",
            },
            "files": {"src/main.kt": {"status": "M", "lines_inserted": 2, "lines_deleted": 0, "size_delta": 40, "size": 100}},
        }
    },
}

PATCH = (
    "diff --git a/src/main.kt b/src/main.kt\n--- a/src/main.kt\n+++ b/src/main.kt\n"
    "@@ -1,3 +1,5 @@\n fun main() {\n+    println(\"runtime verify\")\n+    verify()\n     println(\"hello\")\n }\n"
)


def _json(code, data):
    return code, XSSI + json.dumps(data), {"Content-Type": "application/json; charset=UTF-8"}


def handle(req):
    if not req.path.startswith(PREFIX + "/"):
        return None
    path = req.path[len(PREFIX):]
    authorized = req.headers.get("Authorization") == "Basic " + base64.b64encode(CREDENTIALS.encode()).decode()
    if not path.startswith("/a/") or not authorized:
        return 401, "Unauthorized", {"Content-Type": "text/plain", "WWW-Authenticate": 'Basic realm="Gerrit Code Review"'}
    path = path[len("/a"):]
    if path == "/accounts/self":
        return _json(200, ACCOUNT)
    if not path.startswith("/changes/"):
        return _json(404, {})
    if req.method == "GET" and path.endswith("/patch"):
        return 200, base64.b64encode(PATCH.encode()).decode(), {"Content-Type": "text/plain"}
    if req.method == "GET":
        return _json(200, CHANGE)
    if req.method == "POST" and path.endswith("/review"):
        return _json(200, {"labels": {}})
    return _json(404, {})
