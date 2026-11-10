"""S3-compatible object store (path-style, mounted at /s3/) for the remote-cache e2e scenarios.

  PUT  /s3/{bucket}/{key}   store the object; 200 with an ETag. A key under cas/{xx}/{rest} whose body does not
                            hash to that digest is a 400 BadDigest.
  HEAD /s3/{bucket}/{key}   200 when stored, else 404 (no body, as S3)
  GET  /s3/{bucket}/{key}   the object, or 404 NoSuchKey

Every bucket name exists and holds its own objects, so each runtime pushes to its own bucket. S3CacheBackend signs
with SigV4 only when `cache.remote` has credentials, so anonymous access is allowed, as on a bucket whose policy
grants it, except on a bucket named `signed-*`, which answers an unsigned request 403 AccessDenied. A request that
carries an `Authorization` header must be a valid SigV4 signature for service `s3` with the mock_aws key, secret and
region, else 403 SignatureDoesNotMatch.
"""

import hashlib
import threading

from mock_aws import _header, _signature_ok

PREFIX = "/s3/"
XML = {"Content-Type": "application/xml"}

_lock = threading.Lock()
_objects = {}


def _error(status, code):
    return status, f"<Error><Code>{code}</Code></Error>", dict(XML)


def handle(req):
    if not req.path.startswith(PREFIX):
        return None
    bucket, _, key = req.path[len(PREFIX):].partition("/")
    if not bucket:
        return _error(404, "NoSuchBucket")
    if _header(req, "authorization") is None:
        if bucket.startswith("signed-"):
            return _error(403, "AccessDenied")
    elif not _signature_ok(req, "s3"):
        return _error(403, "SignatureDoesNotMatch")
    if req.method == "PUT":
        parts = key.split("/")
        if len(parts) == 3 and parts[0] == "cas" and hashlib.sha256(req.body).hexdigest() != parts[1] + parts[2]:
            return _error(400, "BadDigest")
        with _lock:
            _objects[(bucket, key)] = req.body
        return 200, b"", {"ETag": f'"{hashlib.md5(req.body).hexdigest()}"'}
    with _lock:
        blob = _objects.get((bucket, key))
    if req.method in ("GET", "HEAD"):
        if blob is None:
            return _error(404, "NoSuchKey")
        return 200, blob, {"Content-Type": "application/octet-stream"}
    return _error(405, "MethodNotAllowed")
