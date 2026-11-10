"""GitHub REST API (GitHub Enterprise layout, mounted at /api/v3) for the mock server.

Accepts the personal token `test-token`, or an installation token it issued itself from
POST /app/installations/{id}/access_tokens; any other bearer gets 401. The token exchange
needs an RS256 app JWT: three parts, alg RS256, an iss, iat <= now < exp, and, when
MOCK_GITHUB_APP_PUBKEY names a PEM public key, a signature that `openssl` verifies.
"""

import base64
import json
import os
import re
import subprocess
import threading
import time
from datetime import datetime, timedelta, timezone

PREFIX = "/api/v3"
PAT = "test-token"

_lock = threading.Lock()
_issued = set()

USER = {"login": "testuser", "id": 1, "name": "Test User"}
HEAD_SHA = "bbb222bbb222bbb222bbb222bbb222bbb222bbb2"

PULL = {
    "id": 200,
    "number": 1,
    "title": "feat: add runtime verification for plugins",
    "body": "This PR adds Layer 2 runtime verification for all plugin types.",
    "html_url": "https://github.example/owner/repo/pull/1",
    "state": "open",
    "draft": False,
    "user": USER,
    "head": {"ref": "feature/runtime-verify", "sha": HEAD_SHA, "label": "owner:feature/runtime-verify"},
    "base": {"ref": "main", "sha": "aaa111aaa111aaa111aaa111aaa111aaa111aaa1", "label": "owner:main"},
    "labels": [{"name": "test"}, {"name": "integration"}],
    "requested_reviewers": [],
    "mergeable": True,
    "mergeable_state": "clean",
    "created_at": "2026-01-15T10:00:00Z",
    "updated_at": "2026-01-15T11:00:00Z",
}

COMMITS = [
    {
        "sha": "abc123def456abc123def456abc123def456abc1",
        "commit": {
            "message": "feat: initial commit\n\nAdds runtime verification scaffold.",
            "author": {"name": "Test User", "email": "test@example.com", "date": "2026-01-15T10:00:00Z"},
        },
    }
]

FILES = [
    {
        "filename": "src/main.kt",
        "status": "modified",
        "additions": 2,
        "deletions": 0,
        "changes": 2,
        "patch": "@@ -1,3 +1,5 @@\n fun main() {\n+    println(\"runtime verify\")\n+    verify()\n     println(\"hello\")\n }",
    }
]

COMMENT = {"id": 301, "body": "Karandash validation posted", "user": USER, "created_at": "2026-01-15T12:00:00Z"}


def _b64url_json(part):
    return json.loads(base64.urlsafe_b64decode(part + "=" * (-len(part) % 4)))


def _jwt_problem(jwt, pubkey):
    """None when [jwt] is a valid app JWT, else why not."""
    parts = jwt.split(".")
    if len(parts) != 3:
        return "not a JWT"
    try:
        header, claims = _b64url_json(parts[0]), _b64url_json(parts[1])
    except ValueError:
        return "undecodable JWT"
    now = time.time()
    if header.get("alg") != "RS256":
        return "alg is not RS256"
    if not claims.get("iss"):
        return "no iss"
    if not (claims.get("iat", now + 1) <= now < claims.get("exp", 0)):
        return "iat/exp do not cover now"
    if pubkey:
        signature = base64.urlsafe_b64decode(parts[2] + "=" * (-len(parts[2]) % 4))
        sig_file = f"{pubkey}.sig"
        with open(sig_file, "wb") as f:
            f.write(signature)
        check = subprocess.run(
            ["openssl", "dgst", "-sha256", "-verify", pubkey, "-signature", sig_file],
            input=f"{parts[0]}.{parts[1]}".encode(),
            capture_output=True,
        )
        if check.returncode != 0:
            return "bad signature"
    return None


def _bearer(req):
    auth = req.headers.get("Authorization", "")
    return auth[len("Bearer "):] if auth.startswith("Bearer ") else ""


def _exchange(req, installation_id):
    problem = _jwt_problem(_bearer(req), os.environ.get("MOCK_GITHUB_APP_PUBKEY"))
    if problem:
        return 401, {"message": f"A JSON web token could not be decoded: {problem}"}
    with _lock:
        token = f"ghs_e2e_{installation_id}_{len(_issued) + 1}"
        _issued.add(token)
    expires = (datetime.now(timezone.utc) + timedelta(hours=1)).strftime("%Y-%m-%dT%H:%M:%SZ")
    return 201, {"token": token, "expires_at": expires}


def handle(req):
    if not req.path.startswith(PREFIX + "/"):
        return None
    path = req.path[len(PREFIX):]
    m = re.fullmatch(r"/app/installations/(\d+)/access_tokens", path)
    if m and req.method == "POST":
        return _exchange(req, m.group(1))
    token = _bearer(req)
    with _lock:
        known = token == PAT or token in _issued
    if not known:
        return 401, {"message": "Bad credentials"}
    return _route(req.method, path, token)


def _route(method, path, token):
    if path == "/user":
        return (200, USER) if token == PAT else (403, {"message": "Resource not accessible by integration"})
    if path == "/installation/repositories":
        return 200, {"total_count": 1, "repositories": [{"full_name": "owner/repo"}]}
    if path == "/rate_limit":
        return 200, {"rate": {"limit": 5000, "remaining": 4999, "reset": 0}}
    m = re.fullmatch(r"/repos/[^/]+/[^/]+/(pulls|issues|commits)/([^/]+)(/.*)?", path)
    if not m:
        return 404, {"message": "Not Found"}
    kind, rest = m.group(1), m.group(3) or ""
    if method == "GET":
        if kind == "commits":
            if rest == "/status":
                return 200, {"state": "success", "statuses": [{"context": "ci/build", "state": "success"}]}
            return 200, {"check_runs": [{"name": "test", "status": "completed", "conclusion": "success"}]}
        return 200, {
            "": PULL,
            "/commits": COMMITS,
            "/files": FILES,
            "/reviews": [],
            "/comments": [],
            "/requested_reviewers": {"users": [], "teams": []},
        }.get(rest, [])
    if method == "PUT" and rest == "/merge":
        return 200, {"merged": True, "sha": HEAD_SHA, "message": "Pull Request successfully merged"}
    return (201 if method == "POST" else 200), COMMENT
