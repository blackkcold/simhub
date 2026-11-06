#!/usr/bin/env python3
import tempfile
import unittest
from pathlib import Path
from check_codeql import check, findings


class CodeQLGateTests(unittest.TestCase):
    def report(self, severity="7.0", **result):
        return {"version": "2.1.0", "runs": [{"tool": {"driver": {"rules": [
            {"id": "test/security", "properties": {"security-severity": severity}}]}},
            "results": [{"ruleId": "test/security", "level": "warning", **result}]}]}

    def test_security_score_blocks_even_warning_or_unchanged(self):
        for value in ("7.0", "9.8", "10"):
            self.assertEqual(len(findings(self.report(value, baselineState="unchanged"))), 1)
        self.assertEqual(findings(self.report("6.9")), [])

    def test_error_and_rule_index_cannot_bypass_gate(self):
        self.assertEqual(len(findings(self.report("1.0", level="error"))), 1)
        report = self.report("9.0")
        report["runs"][0]["results"] = [{"ruleIndex": 0}]
        self.assertEqual(len(findings(report)), 1)

    def test_missing_incomplete_or_invalid_analysis_fails_closed(self):
        with tempfile.TemporaryDirectory() as temporary:
            with self.assertRaises(ValueError):
                check(Path(temporary))
        for invalid in ({}, {"version": "2.1.0", "runs": []}, self.report("NaN"), self.report("infinite")):
            with self.assertRaises((ValueError, TypeError)):
                findings(invalid)
        report = self.report("1.0")
        report["runs"][0]["invocations"] = [{"executionSuccessful": False}]
        with self.assertRaises(ValueError):
            findings(report)

    def test_complete_scan_without_results_passes(self):
        report = self.report()
        report["runs"][0]["results"] = []
        self.assertEqual(findings(report), [])


if __name__ == "__main__":
    unittest.main()
