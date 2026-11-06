#!/usr/bin/env python3
"""Protect standalone entry points and new unittest files from silent omission."""
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
import run_regressions


class RegressionDiscoveryTests(unittest.TestCase):
    def test_standalone_unittest_runner_is_executed_not_empty_discovery(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "test_sandbox.py"
            path.write_text("import unittest\ndef main(): pass\nif __name__=='__main__': main()\n")
            self.assertEqual(run_regressions.python_command(path)[-1], str(path))
            path.write_text("import unittest\nclass NewTest(unittest.TestCase):\n def test_failure_recovery(self): pass\n")
            self.assertIn("discover", run_regressions.python_command(path))

    def test_external_integration_requires_an_explicit_group(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); root.joinpath("scripts").mkdir()
            root.joinpath("scripts/test_external.py").write_text("# simhub-test-group: p2p-integration\nassert True\n")
            with patch.object(run_regressions, "ROOT", root):
                self.assertEqual(list(run_regressions.commands(web=False)), [])
                self.assertEqual(len(list(run_regressions.commands(web=False, groups=["p2p-integration"]))), 1)


if __name__ == "__main__":
    unittest.main()
