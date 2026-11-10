"""Public plugin registry for the plugin install/update scenarios (registry-*).

/registry/v1.json lists e2e-js-plugin 1.0.0, /registry/v2.json lists 1.1.0 (both also list e2e-bad-checksum, whose
registry checksum is wrong). The index needs `Authorization: Bearer reg-token`, the way a private registry would;
downloads are public. The plugin is a tiny JavaScript (.mjs) rule plugin, so no build is needed. Download URLs are
built from the request's Host header, so the registry and its artifacts are reachable at whatever (HTTPS) front the
CLI used.
"""

import hashlib
import json

PLUGIN_ID = "e2e-js-plugin"

TEMPLATE = """export default {
  id: "%(id)s",
  name: "E2E JS Plugin",
  version: "%(version)s",
  type: "RULE",
  defaultPriority: 100,
  isDefaultEnabled: false,
  initialize(context) { return true; },
  shutdown() {},
  contributedRules() { return ["e2e-js-rule"]; },
  defaultConfig() { return { "e2e-js-rule": {} }; },
  validate(ruleId, mergeRequest, config) { return []; }
};
"""

ARTIFACTS = {
    "/registry/dl/%s-1.0.0.mjs" % PLUGIN_ID: (PLUGIN_ID, "1.0.0"),
    "/registry/dl/%s-1.1.0.mjs" % PLUGIN_ID: (PLUGIN_ID, "1.1.0"),
    "/registry/dl/e2e-bad-checksum-1.0.0.mjs": ("e2e-bad-checksum", "1.0.0"),
}


def _artifact(path):
    plugin_id, version = ARTIFACTS[path]
    return (TEMPLATE % {"id": plugin_id, "version": version}).encode()


def _entry(host, plugin_id, version, checksum=None):
    path = "/registry/dl/%s-%s.mjs" % (plugin_id, version)
    return {
        "id": plugin_id,
        "name": "E2E JS Plugin",
        "description": "Tiny JavaScript rule plugin served by the e2e mock registry",
        "author": "e2e",
        "version": version,
        "type": "rule",
        "tags": ["e2e"],
        "url": "https://%s%s" % (host, path),
        "checksum": checksum or "sha256:" + hashlib.sha256(_artifact(path)).hexdigest(),
        "minHostVersion": "1.0.0",
        "repository": "https://example.invalid/e2e",
        "documentation": "https://example.invalid/e2e",
    }


def handle(req):
    host = {k.lower(): v for k, v in req.headers.items()}.get("host", "localhost")
    if req.path in ARTIFACTS:
        return 200, _artifact(req.path), {"Content-Type": "application/octet-stream"}
    version = {"/registry/v1.json": "1.0.0", "/registry/v2.json": "1.1.0"}.get(req.path)
    if version is None:
        return None
    if {k.lower(): v for k, v in req.headers.items()}.get("authorization") != "Bearer reg-token":
        return 401, {"message": "registry token required"}
    entries = [_entry(host, PLUGIN_ID, version), _entry(host, "e2e-bad-checksum", "1.0.0", "sha256:" + "0" * 64)]
    return 200, {"version": "1", "lastUpdated": "2026-10-01T00:00:00Z", "plugins": entries}
