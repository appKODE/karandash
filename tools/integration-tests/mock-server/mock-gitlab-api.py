#!/usr/bin/env python3
"""Mock platform API server for Karandash Layer 2 integration testing.

Returns fake GitLab-format responses so the full validation pipeline
(provider fetch -> rule validation -> reporter -> comment poster -> mutator)
can be exercised against a real Karandash binary without a live GitLab instance.
Other APIs live in sibling modules, each mounted under its own path prefix and tried
before the GitLab routes (see MOUNTS): every module exports handle(req) returning
(status, body[, headers]) or None when the path is not its own.

Usage:
    python3 mock-gitlab-api.py [PORT]   # default: 9999

Environment (optional):
    MOCK_REQUEST_LOG  append "METHOD /path" for every request to this file (lets tests assert writes)
    MOCK_FILES_DIR    serve GET /files/<name> from this directory (remote `extends:` / `{ file: }` sources)
    MOCK_BODY_LOG     append "METHOD /path <body>" for every request with a body (lets tests assert what was sent)
"""

import importlib
import json
import os
import sys
from dataclasses import dataclass
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit

# Sibling modules tried in order before the GitLab routes; each one claims its own path prefix.
MOUNTS = []


@dataclass
class Request:
    method: str
    path: str
    query: dict
    headers: dict
    body: bytes

    def json(self):
        return json.loads(self.body or b"null")

MOCK_USER = {
    "id": 1,
    "username": "testuser",
    "name": "Test User",
    "email": "test@example.com",
}

MOCK_DIFF_REFS = {
    "base_sha": "aaa111aaa111aaa111aaa111aaa111aaa111aaa1",
    "head_sha": "bbb222bbb222bbb222bbb222bbb222bbb222bbb2",
    "start_sha": "ccc333ccc333ccc333ccc333ccc333ccc333ccc3",
}

MOCK_MR = {
    "id": 100,
    "iid": 1,
    "title": "feat: add runtime verification for plugins",
    "description": "This MR adds Layer 2 runtime verification for all plugin types.",
    "source_branch": "feature/runtime-verify",
    "target_branch": "main",
    "author": MOCK_USER,
    "state": "opened",
    "work_in_progress": False,
    "draft": False,
    "labels": ["test", "integration"],
    "approved_by": [],
    "reviewers": [],
    "merge_status": "can_be_merged",
    "detailed_merge_status": "mergeable",
    "has_conflicts": False,
    "head_pipeline": {"id": 1001, "status": "success"},
    "diff_refs": MOCK_DIFF_REFS,
}

MOCK_COMMITS = [
    {
        "id": "abc123def456abc123def456abc123def456abc1",
        "short_id": "abc123d",
        "title": "feat: initial commit",
        "message": "feat: initial commit\n\nAdds runtime verification scaffold.",
        "author_name": "Test User",
        "author_email": "test@example.com",
        "created_at": "2026-01-15T10:00:00Z",
    },
    {
        "id": "def456ghi789def456ghi789def456ghi789def4",
        "short_id": "def456g",
        "title": "feat: add mock server tests",
        "message": "feat: add mock server tests\n\nEnd-to-end pipeline verification.",
        "author_name": "Test User",
        "author_email": "test@example.com",
        "created_at": "2026-01-15T11:00:00Z",
    },
]

MOCK_CHANGES = {
    "changes": [
        {
            "old_path": "src/main.kt",
            "new_path": "src/main.kt",
            "new_file": False,
            "renamed_file": False,
            "deleted_file": False,
            "diff": "@@ -1,3 +1,5 @@\n fun main() {\n+    println(\"runtime verify\")\n+    verify()\n     println(\"hello\")\n }",
        }
    ]
}

MOCK_NOTE = {
    "id": 501,
    "body": "Karandash validation posted",
    "author": MOCK_USER,
    "created_at": "2026-01-15T12:00:00Z",
    "updated_at": "2026-01-15T12:00:00Z",
    "system": False,
    "noteable_id": 100,
    "noteable_type": "MergeRequest",
}

MOCK_DISCUSSION = {"id": "disc-001"}


class MockGitLabHandler(BaseHTTPRequestHandler):
    """Handles GitLab API v4 merge-request endpoints; MOUNTS modules handle the other APIs."""

    def _drain_body(self):
        """Read the request body (always, to avoid a connection reset) and return it."""
        length = int(self.headers.get("Content-Length", 0))
        return self.rfile.read(length) if length > 0 else b""

    def _record(self, body=b""):
        log = os.environ.get("MOCK_REQUEST_LOG")
        if log:
            with open(log, "a", encoding="utf-8") as f:
                f.write(f"{self.command} {self.path.split('?')[0]}\n")
        body_log = os.environ.get("MOCK_BODY_LOG")
        if body_log and body:
            with open(body_log, "a", encoding="utf-8") as f:
                f.write(f"{self.command} {self.path.split('?')[0]} {body.decode('utf-8', 'replace')}\n")

    def _mounted(self):
        """Record the request and let a MOUNTS module answer it; False when none claims the path."""
        body = self._drain_body()
        self._record(body)
        parts = urlsplit(self.path)
        req = Request(self.command, parts.path, parse_qs(parts.query), dict(self.headers.items()), body)
        for module in MOUNTS:
            answer = module.handle(req)
            if answer is not None:
                code, data, *headers = answer
                self._respond(code, data, headers[0] if headers else None)
                return True
        return False

    def _serve_file(self, name):
        base = os.environ.get("MOCK_FILES_DIR")
        target = os.path.join(base, os.path.basename(name)) if base else None
        if not target or not os.path.isfile(target):
            self._respond(404, {"error": "not found"})
            return
        with open(target, "rb") as f:
            body = f.read()
        self.send_response(200)
        self.send_header("Content-Type", "text/plain")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self._mounted():
            return
        path = self.path.split("?")[0]

        if path.startswith("/files/"):
            self._serve_file(path[len("/files/"):])
            return
        if path == "/api/v4/user":
            self._respond(200, MOCK_USER)
            return
        if "/merge_requests/" not in path:
            self._respond(404, {"error": "not found"})
            return

        if path.endswith("/commits"):
            self._respond(200, MOCK_COMMITS)
        elif path.endswith("/changes"):
            self._respond(200, MOCK_CHANGES)
        elif path.endswith("/notes"):
            self._respond(200, [])
        elif "/merge_requests/" in path:
            self._respond(200, MOCK_MR)
        else:
            self._respond(404, {"error": "not found"})

    def do_POST(self):
        if self._mounted():
            return
        path = self.path.split("?")[0]

        if path.endswith("/discussions"):
            self._respond(201, MOCK_DISCUSSION)
        elif path.endswith("/notes"):
            self._respond(201, MOCK_NOTE)
        else:
            self._respond(201, {"id": 1, "body": "ok"})

    def do_PUT(self):
        if self._mounted():
            return
        self._respond(200, MOCK_MR)

    def do_PATCH(self):
        if not self._mounted():
            self._respond(404, {"error": "not found"})

    def do_DELETE(self):
        if not self._mounted():
            self._respond(404, {"error": "not found"})

    def do_HEAD(self):
        if not self._mounted():
            self._respond(404, {"error": "not found"})

    def _respond(self, code, data, headers=None):
        """Send [data] as JSON, or as-is when it is bytes or a str (with its own Content-Type in [headers])."""
        raw = data if isinstance(data, bytes) else data.encode("utf-8") if isinstance(data, str) else json.dumps(data).encode("utf-8")
        self.send_response(code)
        headers = headers or {}
        self.send_header("Content-Type", headers.pop("Content-Type", "application/json"))
        for name, value in headers.items():
            self.send_header(name, value)
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(raw)

    def log_message(self, format, *args):
        """Suppress request logging to keep test output clean."""
        pass


def main():
    # every mock_<api>.py next to this file is a mount
    here = os.path.dirname(os.path.abspath(__file__))
    for name in sorted(f[:-3] for f in os.listdir(here) if f.startswith("mock_") and f.endswith(".py")):
        MOUNTS.append(importlib.import_module(name))
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 9999
    server = ThreadingHTTPServer(("0.0.0.0", port), MockGitLabHandler)
    print(f"Mock GitLab API listening on :{port}", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
