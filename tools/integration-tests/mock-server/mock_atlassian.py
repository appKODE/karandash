"""Jira Cloud REST v3 (/jira) and Confluence REST (/confluence) for the enrichment scenarios (enrich-*).

Both need HTTP Basic e2e@example.com:atlassian-token and answer 401 otherwise.
Jira: issue ABC-123 is "In Progress", assigned to Ada; any other key is 404.
Confluence: page 123456 ("Design notes") has a small storage-format body; any other page is 404.
"""

import base64

BASIC = "Basic " + base64.b64encode(b"e2e@example.com:atlassian-token").decode()

ISSUE = {
    "id": "10001",
    "key": "ABC-123",
    "fields": {
        "summary": "Runtime verification for plugins",
        "description": None,
        "issuetype": {"name": "Story"},
        "priority": {"name": "High"},
        "status": {"name": "In Progress", "statusCategory": {"name": "In Progress"}},
        "labels": ["e2e"],
        "assignee": {"displayName": "Ada Lovelace"},
        "issuelinks": [],
    },
}

PAGE = {
    "id": "123456",
    "title": "Design notes",
    "version": {"number": 3},
    "body": {"storage": {"value": "<h1>Design</h1><p>Plugins are verified at load time.</p>"}},
    "_links": {"webui": "/spaces/ENG/pages/123456/Design+notes"},
}


def handle(req):
    for prefix in ("/jira/", "/confluence/"):
        if req.path.startswith(prefix):
            break
    else:
        return None
    if {k.lower(): v for k, v in req.headers.items()}.get("authorization") != BASIC:
        return 401, {"message": "Basic auth required"}
    if req.path == "/jira/rest/api/3/issue/ABC-123":
        return 200, ISSUE
    if req.path == "/confluence/rest/api/content/123456":
        return 200, PAGE
    return 404, {"message": "Not Found"}
