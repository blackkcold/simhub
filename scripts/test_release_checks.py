#!/usr/bin/env python3
"""Regression tests for mandatory independent release scan gates."""
import unittest
from scripts.verify_release_checks import REQUIRED_CODEQL, verdict

SHA = "a" * 40


def workflow(name, conclusion="success", *, sha=SHA, event="push", number=1, branch="main"):
    return {"name": name, "status": "completed", "conclusion": conclusion,
            "head_sha": sha, "head_branch": branch, "event": event, "id": number}


def scans(*, sha=SHA, overrides=None):
    values = overrides or {}
    return [{"name": name, "status": "completed", "conclusion": values.get(name, "success"),
             "head_sha": sha, "id": i + 10}
            for i, name in enumerate(REQUIRED_CODEQL)]


class ReleaseChecksTests(unittest.TestCase):
    def setUp(self):
        self.runs = [workflow("CI"), workflow("Security scan")]

    def test_all_independent_push_gates_required(self):
        self.assertEqual(verdict(self.runs, scans(), SHA, "main")[0], "ready")
        self.assertEqual(verdict(self.runs, [], SHA, "main")[0], "waiting")
        self.assertEqual(verdict(self.runs[:1], scans(), SHA, "main")[0], "waiting")
        self.assertEqual(verdict(self.runs, scans()[:-1], SHA, "main")[0], "waiting")

    def test_native_pr_aggregate_neutral_does_not_hide_successful_push_scans(self):
        neutral = {"name": "CodeQL", "status": "completed", "conclusion": "neutral",
                   "head_sha": SHA, "id": 99}
        self.assertEqual(verdict(self.runs, scans() + [neutral], SHA, "main")[0], "ready")

    def test_real_codeql_matrix_failure_blocks_release(self):
        changed = {"codeql (python)": "failure"}
        self.assertEqual(verdict(self.runs, scans(overrides=changed), SHA, "main")[0], "failed")
        changed = {"codeql compatibility (java-kotlin)": "neutral"}
        self.assertEqual(verdict(self.runs, scans(overrides=changed), SHA, "main")[0], "failed")

    def test_failed_security_scan_blocks_release(self):
        runs = [workflow("CI"), workflow("Security scan", "failure")]
        self.assertEqual(verdict(runs, scans(), SHA, "main")[0], "failed")

    def test_unrelated_sha_and_pr_runs_cannot_satisfy_gate(self):
        self.assertEqual(verdict(self.runs, scans(sha="b" * 40), SHA, "main")[0], "waiting")
        runs = [workflow("CI"), workflow("Security scan", event="pull_request")]
        self.assertEqual(verdict(runs, scans(), SHA, "main")[0], "waiting")

    def test_newest_run_and_job_win(self):
        runs = [workflow("CI", "failure", number=1), workflow("CI", number=2),
                workflow("Security scan")]
        self.assertEqual(verdict(runs, scans(), SHA, "main")[0], "ready")
        bad = scans(overrides={"codeql (python)": "failure"})
        good = scans()
        good[0]["id"] = 200
        self.assertEqual(verdict(self.runs, bad + good, SHA, "main")[0], "ready")

    def test_develop_requires_p2p_preview_at_exact_head(self):
        runs = [workflow("CI", branch="develop"), workflow("Security scan", branch="develop")]
        self.assertEqual(verdict(runs, scans(), SHA, "develop")[0], "waiting")
        runs.append(workflow("P2P isolated preview", branch="develop"))
        self.assertEqual(verdict(runs, scans(), SHA, "develop")[0], "ready")
        runs[-1] = workflow("P2P isolated preview", "failure", branch="develop")
        self.assertEqual(verdict(runs, scans(), SHA, "develop")[0], "failed")

    def test_reject_unrecognized_branch(self):
        self.assertEqual(verdict([], [], SHA, "feature")[0], "failed")


if __name__ == "__main__":
    unittest.main()
