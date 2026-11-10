"""AWS CodeCommit JSON API (mounted at /aws/) and STS (mounted at /aws-sts/) for the mock server.

Every request must carry a SigV4 `Authorization` header of the shape
`AWS4-HMAC-SHA256 Credential=<key>/<yyyymmdd>/eu-west-1/<service>/aws4_request, SignedHeaders=...host..., Signature=<hex>`,
and the signature must verify against the fixture secret for the request's real method, path, Host and body, so a
client signing for the wrong host or path gets 403. Credentials other than the fixture key get 403 too.
Served: one pull request, repo #1. Each CodeCommit call appends `AWS <operation>` to MOCK_REQUEST_LOG; neither
secrets nor signatures are logged.
"""

import base64
import hashlib
import hmac
import os
import re

CODECOMMIT = "/aws/"
STS = "/aws-sts/"
REGION = "eu-west-1"
ACCESS_KEY = "AKIDKARANDASHTEST"
SECRET = "karandash-mock-secret"  # fixture-only secret, also written into the e2e config
AUTH_RE = re.compile(
    r"AWS4-HMAC-SHA256 Credential=(?P<key>[A-Z0-9]+)/(?P<date>\d{8})/(?P<region>[a-z0-9-]+)/(?P<service>[a-z0-9]+)/aws4_request, "
    r"SignedHeaders=(?P<signed>[a-z0-9;-]+), Signature=(?P<sig>[0-9a-f]{64})"
)

HEAD = "bbb222bbb222bbb222bbb222bbb222bbb222bbb2"
BASE = "aaa111aaa111aaa111aaa111aaa111aaa111aaa1"
AUTHOR = "arn:aws:iam::123456789012:user/alice"
PULL = {
    "pullRequestId": "1",
    "title": "feat: add runtime verification for plugins",
    "description": "This PR adds Layer 2 runtime verification for all plugin types.",
    "pullRequestStatus": "OPEN",
    "authorArn": AUTHOR,
    "creationDate": 1768471200.0,
    "lastActivityDate": 1768474800.0,
    "revisionId": "rev-1",
    "pullRequestTargets": [
        {
            "repositoryName": "repo",
            "sourceReference": "refs/heads/feature/runtime-verify",
            "destinationReference": "refs/heads/main",
            "sourceCommit": HEAD,
            "destinationCommit": BASE,
            "mergeBase": BASE,
            "mergeMetadata": {"isMerged": False},
        }
    ],
}
COMMITS = {
    HEAD: {"commitId": HEAD, "parents": [BASE], "message": "feat: initial commit",
           "author": {"name": "Alice", "email": "alice@example.com", "date": "1768471200 +0000"}},
}
BLOBS = {
    "blob-before": "fun main() {\n    println(\"hello\")\n}\n",
    "blob-after": "fun main() {\n    println(\"runtime verify\")\n    verify()\n    println(\"hello\")\n}\n",
}
DIFFERENCES = {
    "differences": [
        {
            "changeType": "M",
            "beforeBlob": {"blobId": "blob-before", "path": "src/main.kt", "mode": "100644"},
            "afterBlob": {"blobId": "blob-after", "path": "src/main.kt", "mode": "100644"},
        }
    ]
}
COMMENT = {"commentId": "c-1", "content": "Karandash validation posted", "authorArn": AUTHOR, "creationDate": 1768478400.0}
CALLER = (
    '<GetCallerIdentityResponse xmlns="https://sts.amazonaws.com/doc/2011-06-15/"><GetCallerIdentityResult>'
    "<Arn>arn:aws:iam::123456789012:user/karandash</Arn><UserId>AIDEXAMPLE</UserId><Account>123456789012</Account>"
    "</GetCallerIdentityResult></GetCallerIdentityResponse>"
)


def _header(req, name):
    return next((v for k, v in req.headers.items() if k.lower() == name), None)


def _hmac(key, msg):
    return hmac.new(key, msg.encode(), hashlib.sha256).digest()


def _signature_ok(req, service):
    """The SigV4 header has the expected shape and its signature matches the request as received."""
    m = AUTH_RE.fullmatch(_header(req, "authorization") or "")
    if not m or m["key"] != ACCESS_KEY or m["region"] != REGION or m["service"] != service:
        return False
    signed = m["signed"].split(";")
    amz_date = _header(req, "x-amz-date") or ""
    payload_hash = hashlib.sha256(req.body).hexdigest()
    if "host" not in signed or not amz_date.startswith(m["date"]) or _header(req, "x-amz-content-sha256") != payload_hash:
        return False
    headers = "".join(f"{name}:{(_header(req, name) or '').strip()}\n" for name in signed)
    canonical = "\n".join([req.method, req.path, "", headers, m["signed"], payload_hash])
    scope = f"{m['date']}/{REGION}/{service}/aws4_request"
    to_sign = "\n".join(["AWS4-HMAC-SHA256", amz_date, scope, hashlib.sha256(canonical.encode()).hexdigest()])
    key = ("AWS4" + SECRET).encode()
    for part in (m["date"], REGION, service, "aws4_request"):
        key = _hmac(key, part)
    return hmac.compare_digest(hmac.new(key, to_sign.encode(), hashlib.sha256).hexdigest(), m["sig"])


def _record(operation):
    log = os.environ.get("MOCK_REQUEST_LOG")
    if log:
        with open(log, "a", encoding="utf-8") as f:
            f.write(f"AWS {operation}\n")


def _codecommit(operation, body):
    if operation == "GetPullRequest":
        return 200, {"pullRequest": PULL}
    if operation == "GetCommit":
        commit = COMMITS.get(body.get("commitId"))
        return (200, {"commit": commit}) if commit else (400, {"__type": "CommitDoesNotExistException"})
    if operation == "GetDifferences":
        return 200, DIFFERENCES
    if operation == "GetBlob":
        content = BLOBS.get(body.get("blobId"))
        if content is None:
            return 400, {"__type": "BlobIdDoesNotExistException"}
        return 200, {"content": base64.b64encode(content.encode()).decode()}
    if operation == "GetPullRequestApprovalStates":
        return 200, {"approvals": []}
    if operation == "GetCommentsForPullRequest":
        return 200, {"commentsForPullRequestData": []}
    if operation in ("PostCommentForPullRequest", "UpdateComment"):
        return 200, {"comment": COMMENT}
    return 400, {"__type": "InvalidActionException", "message": f"unsupported {operation}"}


def handle(req):
    if req.method != "POST" or req.path not in (CODECOMMIT, STS):
        return None
    if req.path == STS:
        if not _signature_ok(req, "sts"):
            return 403, "<ErrorResponse><Error><Code>SignatureDoesNotMatch</Code></Error></ErrorResponse>", {"Content-Type": "text/xml"}
        return 200, CALLER, {"Content-Type": "text/xml"}
    operation = (_header(req, "x-amz-target") or "").removeprefix("CodeCommit_20150413.")
    _record(operation)
    if not _signature_ok(req, "codecommit"):
        return 403, {"__type": "UnrecognizedClientException", "message": "The security token included in the request is invalid."}
    return _codecommit(operation, req.json() or {})
