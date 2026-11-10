#!/usr/bin/env python3
"""Read-only dump of a GitLab project's MR review history for `karandash stats --audit`.

Only ever issues GET requests, through `glab api --hostname <host>` (auth comes from glab).
Writes <out>/<project dir>/<iid>.json (schema = CorpusMr in cli-ai .../stats/audit/CorpusModel.kt)
plus <out>/manifest.json. Resumable: an MR whose file already exists is skipped.

Example:
  python3 tools/audit/fetch_gitlab_corpus.py --hostname gitlab.example.com --project group/app \\
      --since 2026-01-01 --until 2026-07-01 --bot karandash-bot --out out/corpus
"""
import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import time
import urllib.parse

CONTEXT_LINES = 3
PER_PAGE = 100
MAX_RETRIES = 6
RETRY_SLEEP_SECONDS = 10


class GlabError(RuntimeError):
    pass


def _glab(host, path):
    """GET [path]; sleeps and retries on HTTP 429, raises GlabError on any other failure."""
    for attempt in range(MAX_RETRIES):
        proc = subprocess.run(["glab", "api", "--hostname", host, path],
                              capture_output=True, text=True, check=False)
        if proc.returncode == 0:
            return proc.stdout
        if "429" in proc.stderr:
            time.sleep(RETRY_SLEEP_SECONDS * (attempt + 1))
            continue
        raise GlabError(f"GET {path}: {proc.stderr.strip()}")
    raise GlabError(f"GET {path}: still rate limited after {MAX_RETRIES} attempts")


def get_json(host, path):
    return json.loads(_glab(host, path))


def get_text(host, path):
    """Raw body of [path], or None when GitLab refuses it (deleted file, missing ref)."""
    try:
        return _glab(host, path)
    except GlabError:
        return None


def paginate(host, path, per_page=PER_PAGE):
    """All items of a list endpoint, page by page until a short page."""
    sep = "&" if "?" in path else "?"
    items, page = [], 1
    while True:
        batch = get_json(host, f"{path}{sep}per_page={per_page}&page={page}")
        items.extend(batch)
        if len(batch) < per_page:
            return items
        page += 1


def window(text, start, count):
    """[count] lines of [text] from 1-based line [start]; None when the file is missing."""
    if text is None:
        return None
    return text.splitlines()[start - 1:start - 1 + count]


def snippet_window(text, line):
    """(start line, lines) of the +-CONTEXT_LINES window around 1-based [line], clamped to the file."""
    if text is None or line is None:
        return None
    start = max(1, line - CONTEXT_LINES)
    return start, window(text, start, line + CONTEXT_LINES - start + 1)


def _user(obj):
    return (obj or {}).get("username")


# GitLab project/group access tokens post as project_<id>_bot_<hash> / group_<id>_bot_<hash>.
TOKEN_BOT = re.compile(r"^(project|group)_\d+_bot")


def is_bot(user, bots):
    name = _user(user) or ""
    return name in bots or bool((user or {}).get("bot")) or bool(TOKEN_BOT.match(name))


def note_record(note, bots):
    pos = note.get("position") or None
    author = _user(note.get("author")) or ""
    return {
        "id": note["id"],
        "author": author,
        "isBot": is_bot(note.get("author"), bots),
        "body": note.get("body") or "",
        "createdAt": note.get("created_at") or "",
        "system": bool(note.get("system")),
        "resolved": bool(note.get("resolved")),
        "resolvedBy": _user(note.get("resolved_by")),
        "position": pos and {
            "newPath": pos.get("new_path"),
            "newLine": pos.get("new_line"),
            "oldLine": pos.get("old_line"),
            "headSha": pos.get("head_sha"),
        },
    }


def diff_hash(changes):
    """sha256 over the MR's (path, diff) pairs in path order: equal for re-opened copies of one change."""
    digest = hashlib.sha256()
    for c in sorted(changes, key=lambda c: (c.get("new_path") or "", c.get("old_path") or "")):
        digest.update(f"{c.get('old_path')}\0{c.get('new_path')}\0{c.get('diff')}\0".encode())
    return digest.hexdigest()


class Fetcher:
    def __init__(self, host, project, bots):
        self.host, self.bots = host, bots
        self.base = f"projects/{urllib.parse.quote(project, safe='')}"
        self.files = {}

    def file(self, path, sha):
        key = (path, sha)
        if key not in self.files:
            enc = urllib.parse.quote(path, safe="")
            self.files[key] = get_text(self.host, f"{self.base}/repository/files/{enc}/raw?ref={sha}")
        return self.files[key]

    def anchor(self, rec, final_sha):
        pos = rec["position"] or {}
        if not pos.get("newPath") or not pos.get("headSha"):
            return None
        win = snippet_window(self.file(pos["newPath"], pos["headSha"]), pos.get("newLine"))
        if win is None:
            return None
        start, lines = win
        final = window(self.file(pos["newPath"], final_sha), start, len(lines)) if final_sha else None
        return {"startLine": start, "snippet": lines, "finalSnippet": final}

    def mr(self, project, mr):
        iid, mr_path = mr["iid"], f"{self.base}/merge_requests/{mr['iid']}"
        final_sha = mr.get("sha") or ""
        changes = get_json(self.host, f"{mr_path}/changes").get("changes") or []
        versions = paginate(self.host, f"{mr_path}/versions")
        discussions = []
        for d in paginate(self.host, f"{mr_path}/discussions"):
            notes = []
            for n in d.get("notes") or []:
                rec = note_record(n, self.bots)
                # limit: reactions and anchors only for bot notes (the audit labels bot findings only);
                # fetch them for every note if replies ever get labelled too.
                if rec["isBot"]:
                    awards = paginate(self.host, f"{mr_path}/notes/{n['id']}/award_emoji")
                    rec["awards"] = [{"name": a["name"], "user": _user(a.get("user")) or ""} for a in awards]
                    if rec["position"]:
                        rec["anchor"] = self.anchor(rec, final_sha)
                notes.append(rec)
            discussions.append({"id": d["id"], "notes": notes})
        return {
            "project": project,
            "iid": iid,
            "createdAt": mr.get("created_at") or "",
            "mergedAt": mr.get("merged_at"),
            "state": mr.get("state") or "",
            "diffHash": diff_hash(changes),
            "finalHeadSha": final_sha,
            "versions": [{"headSha": v["head_commit_sha"], "createdAt": v.get("created_at") or ""}
                         for v in versions],
            "discussions": discussions,
        }


def write_json(path, data):
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2, sort_keys=True)
        f.write("\n")
    os.replace(tmp, path)  # atomic: an interrupted run never leaves a half file that resume would skip


def update_manifest(out, project, args, iids):
    path = os.path.join(out, "manifest.json")
    manifest = {"projects": []}
    if os.path.exists(path):
        with open(path, encoding="utf-8") as f:
            manifest = json.load(f)
    entries = [p for p in manifest.get("projects", []) if p.get("project") != project]
    entries.append({"project": project, "hostname": args.hostname, "since": args.since, "until": args.until,
                    "bots": sorted(args.bot), "iids": sorted(iids)})
    manifest["projects"] = sorted(entries, key=lambda p: p["project"])
    write_json(path, manifest)


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("--hostname", required=True)
    p.add_argument("--project", required=True, help="numeric id or full path, e.g. group/app")
    p.add_argument("--since", help="created_after, YYYY-MM-DD")
    p.add_argument("--until", help="created_before, YYYY-MM-DD")
    p.add_argument("--bot", action="append", default=[], help="bot username (repeatable)")
    p.add_argument("--out", required=True)
    args = p.parse_args(argv)

    fetcher = Fetcher(args.hostname, args.project, set(args.bot))
    query = {"state": "all", "scope": "all", "order_by": "created_at", "sort": "asc"}
    if args.since:
        query["created_after"] = f"{args.since}T00:00:00Z"
    if args.until:
        query["created_before"] = f"{args.until}T00:00:00Z"
    mrs = paginate(args.hostname, f"{fetcher.base}/merge_requests?{urllib.parse.urlencode(query)}")

    project_dir = os.path.join(args.out, args.project.replace("/", "_"))
    os.makedirs(project_dir, exist_ok=True)
    for i, mr in enumerate(mrs, 1):
        target = os.path.join(project_dir, f"{mr['iid']}.json")
        if os.path.exists(target):
            continue
        print(f"[{i}/{len(mrs)}] !{mr['iid']}", file=sys.stderr)
        write_json(target, fetcher.mr(args.project, mr))
    update_manifest(args.out, args.project, args, [m["iid"] for m in mrs])
    return 0


if __name__ == "__main__":
    sys.exit(main())
