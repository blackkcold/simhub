#!/usr/bin/env python3
"""Regression tests for mandatory independent release security gates."""
import unittest
from scripts.verify_release_checks import verdict

SHA = "a" * 40


def workflow(name, conclusion="success", *, sha=SHA, event="push", number=1):
    return {"name": name, "status": "completed", "conclusion": conclusion,
            "head_sha": sha, "head_branch": "main", "event": event, "id": number}


def codeql(conclusion="success", *, sha=SHA, number=3):
    return {"name": "CodeQL", "status": "completed", "conclusion": conclusion,
            "head_sha": sha, "id": number}


class ReleaseChecksTests(unittest.TestCase):
    def test_release_requires_all_three_gates_on_same_commit(self):
        runs = [workflow("CI"), workflow("Security scan")]
        self.assertEqual(verdict(runs, [codeql()], SHA, "main")[0], "ready")
        self.assertEqual(verdict(runs, [], SHA, "main")[0], "waiting")
        self.assertEqual(verdict(runs[:1], [codeql()], SHA, "main")[0], "waiting")

    def test_failed_codeql_blocks_release(self):
        runs = [workflow("CI"), workflow("Security scan")]
        self.assertEqual(verdict(runs, [codeql("failure")], SHA, "main")[0], "failed")
        self.assertEqual(verdict(runs, [codeql("neutral")], SHA, "main")[0], "waiting")

    def test_failed_scan_blocks_even_when_build_is_green(self):
        runs = [workflow("CI"), workflow("Security scan", "failure")]
        self.assertEqual(verdict(runs, [codeql()], SHA, "main")[0], "failed")

    def test_old_or_pr_event_cannot_satisfy_gate(self):
        runs = [workflow("CI"), workflow("Security scan", sha="b" * 40)]
        self.assertEqual(verdict(runs, [codeql()], SHA, "main")[0], "waiting")
        runs = [workflow("CI"), workflow("Security scan", event="pull_request")]
        self.assertEqual(verdict(runs, [codeql()], SHA, "main")[0], "waiting")

    def test_latest_run_wins_not_superseded_failure(self):
        runs = [workflow("CI", "failure", number=1), workflow("CI", number=2),
                workflow("Security scan")]
        self.assertEqual(verdict(runs, [codeql()], SHA, "main")[0], "ready")
        self.assertEqual(verdict(runs, [codeql("failure", number=1), codeql(number=2)],
                                 SHA, "main")[0], "ready")

    def test_reject_unrecognized_branch(self):
        self.assertEqual(verdict([], [], SHA, "feature")[0], "failed")


if __name__ == "__main__":
    unittest.main()
