"""Offline tests for fetch_gitlab_corpus: pagination, 429 retry and the snippet window (subprocess stubbed)."""
import json
import os
import subprocess
import sys
import unittest
from unittest import mock

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import fetch_gitlab_corpus as fetch  # noqa: E402


def done(stdout="", code=0, stderr=""):
    return subprocess.CompletedProcess(args=[], returncode=code, stdout=stdout, stderr=stderr)


class PaginateTest(unittest.TestCase):
    def test_follows_pages_until_a_short_page(self):
        pages = {1: list(range(3)), 2: list(range(3, 6)), 3: [6]}
        calls = []

        def run(cmd, **_):
            calls.append(cmd)
            page = int(cmd[-1].split("page=")[-1])
            return done(json.dumps(pages[page]))

        with mock.patch.object(fetch.subprocess, "run", side_effect=run):
            items = fetch.paginate("gl.example", "projects/1/merge_requests?state=all", per_page=3)

        self.assertEqual(list(range(7)), items)
        self.assertEqual(3, len(calls))
        self.assertEqual(["glab", "api", "--hostname", "gl.example"], calls[0][:4])
        self.assertIn("projects/1/merge_requests?state=all&per_page=3&page=1", calls[0][-1])

    def test_only_get_is_ever_issued(self):
        with mock.patch.object(fetch.subprocess, "run", return_value=done("[]")) as run:
            fetch.paginate("gl.example", "projects/1/merge_requests", per_page=3)
        cmd = run.call_args[0][0]
        self.assertNotIn("-X", cmd)
        self.assertNotIn("--method", cmd)

    def test_sleeps_and_retries_on_429(self):
        replies = [done(code=1, stderr="HTTP 429 Too Many Requests"), done("[1]")]
        with mock.patch.object(fetch.subprocess, "run", side_effect=replies), \
                mock.patch.object(fetch.time, "sleep") as sleep:
            items = fetch.paginate("gl.example", "projects/1/x", per_page=3)
        self.assertEqual([1], items)
        sleep.assert_called_once()

    def test_other_errors_raise(self):
        with mock.patch.object(fetch.subprocess, "run", return_value=done(code=1, stderr="HTTP 403")):
            with self.assertRaises(fetch.GlabError):
                fetch.get_json("gl.example", "projects/1")


class SnippetWindowTest(unittest.TestCase):
    TEXT = "\n".join(f"line{i}" for i in range(1, 11))

    def test_three_lines_each_side(self):
        self.assertEqual((4, ["line4", "line5", "line6", "line7", "line8", "line9", "line10"]),
                         fetch.snippet_window(self.TEXT, 7))

    def test_clamped_at_file_start(self):
        self.assertEqual((1, ["line1", "line2", "line3", "line4"]), fetch.snippet_window(self.TEXT, 1))

    def test_same_window_at_final_head(self):
        self.assertEqual(["line4", "line5"], fetch.window(self.TEXT, 4, 2))
        self.assertIsNone(fetch.window(None, 4, 2))

    def test_missing_file_or_line(self):
        self.assertIsNone(fetch.snippet_window(None, 3))
        self.assertIsNone(fetch.snippet_window(self.TEXT, None))


class NoteRecordTest(unittest.TestCase):
    def test_access_token_bot_detected_without_list(self):
        self.assertTrue(fetch.is_bot({"username": "project_1927_bot_d4a3"}, set()))
        self.assertTrue(fetch.is_bot({"username": "x", "bot": True}, set()))
        self.assertFalse(fetch.is_bot({"username": "project_lead"}, set()))

    def test_bot_flag_from_list_and_position(self):
        note = {"id": 5, "author": {"username": "kbot"}, "body": "b", "created_at": "t", "system": False,
                "resolved": True, "resolved_by": {"username": "alice"},
                "position": {"new_path": "a.kt", "new_line": 3, "old_line": None, "head_sha": "h"}}
        rec = fetch.note_record(note, {"kbot"})
        self.assertTrue(rec["isBot"])
        self.assertEqual("alice", rec["resolvedBy"])
        self.assertEqual({"newPath": "a.kt", "newLine": 3, "oldLine": None, "headSha": "h"}, rec["position"])
        self.assertFalse(fetch.note_record(dict(note, author={"username": "bob"}), {"kbot"})["isBot"])


if __name__ == "__main__":
    unittest.main()
