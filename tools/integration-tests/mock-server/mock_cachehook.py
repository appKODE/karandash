"""Webhook receiver and HTTP remote-cache backend for the mock server.

  POST /hook/results            the webhook reporter's target; 401 unless `Authorization: Bearer hook-secret`
                                and `X-Source: karandash` are sent, else 200 and a `HOOK-AUTH ok /hook/results`
                                line in MOCK_REQUEST_LOG (proves the credentials arrived, not just the POST)
  POST /hook/slack              stand-in Slack incoming webhook: 200 `ok` (Slack's own reply)
  PUT|GET|HEAD /cachebk[/{ns}]/cas/{xx}/{rest}   HTTP remote cache blob store (core/cache HttpCacheBackend layout).
                                A PUT whose body does not hash to the digest in the path is a 400; HEAD is 200
                                only for a stored blob, so a second `cache push` skipping every blob proves they
                                were stored.
  PUT /cachebk-refuse/...       a backend that refuses every blob: 400 (a push that must count as failed)
"""

import hashlib
import os
import re
import threading

# /cachebk[/{namespace}]/cas/{xx}/{rest}: each namespace (one per e2e runtime) holds its own blobs
CACHE_BLOB = re.compile(r"/cachebk(?:/(?P<ns>[^/]+))?/cas/(?P<digest>[0-9a-f]{2})/(?P<rest>[0-9a-f]+)")

_lock = threading.Lock()
_blobs = {}


def handle(req):
    if req.path == "/hook/results" and req.method == "POST":
        if req.headers.get("Authorization") != "Bearer hook-secret" or req.headers.get("X-Source") != "karandash":
            return 401, {"error": "bad webhook credentials"}
        log = os.environ.get("MOCK_REQUEST_LOG")
        if log:
            with open(log, "a", encoding="utf-8") as f:
                f.write("HOOK-AUTH ok /hook/results\n")
        return 200, {"ok": True}
    if req.path == "/hook/slack" and req.method == "POST":
        return 200, "ok", {"Content-Type": "text/plain"}
    if req.path.startswith("/cachebk-refuse/"):
        return (400, {"error": "refused"}) if req.method == "PUT" else (404, {})
    m = CACHE_BLOB.fullmatch(req.path)
    if m:
        key = (m["ns"], m["digest"] + m["rest"])
        if req.method == "PUT":
            if hashlib.sha256(req.body).hexdigest() != key[1]:
                return 400, {"error": "body does not match digest"}
            with _lock:
                _blobs[key] = req.body
            return 201, {}
        if req.method in ("GET", "HEAD"):
            with _lock:
                blob = _blobs.get(key)
            return (200, blob, {"Content-Type": "application/octet-stream"}) if blob is not None else (404, {})
    return None
