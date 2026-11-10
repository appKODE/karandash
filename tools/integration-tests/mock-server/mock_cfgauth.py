"""Remote config files that need credentials, for the `extends:` auth scenarios (cfg-auth-*).

/cfgauth/<scheme>/base.yaml answers 200 with a small base config only when the request carries the credential its
scheme requires, and 401 otherwise:
  bearer  Authorization: Bearer cfg-bearer-token
  header  X-Api-Key: cfg-api-key
  basic   Authorization: Basic base64(cfg-user:cfg-pass)
"""

import base64

BASE = b'rules:\n  title-format:\n    pattern: "^fix: .+"\n    max-length: 10\n'

EXPECTED = {
    "bearer": ("Authorization", "Bearer cfg-bearer-token"),
    "header": ("X-Api-Key", "cfg-api-key"),
    "basic": ("Authorization", "Basic " + base64.b64encode(b"cfg-user:cfg-pass").decode()),
}


def handle(req):
    parts = req.path.strip("/").split("/")
    if len(parts) != 3 or parts[0] != "cfgauth" or parts[2] != "base.yaml" or parts[1] not in EXPECTED:
        return None
    name, value = EXPECTED[parts[1]]
    sent = {k.lower(): v for k, v in req.headers.items()}.get(name.lower())
    if sent != value:
        return 401, b"unauthorized", {"Content-Type": "text/plain"}
    return 200, BASE, {"Content-Type": "application/x-yaml"}
