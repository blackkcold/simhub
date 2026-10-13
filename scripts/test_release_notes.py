#!/usr/bin/env python3
from pathlib import Path
import unittest

from release_notes import section_for_version, render_notes, summary_for_version


class ReleaseNotesTests(unittest.TestCase):
    def test_changelog_release_section_is_version_specific(self):
        source=Path("CHANGELOG.md").read_text(encoding="utf-8")
        notes=render_notes(source,"0.16.3")
        self.assertIn("Categorized device health dashboards",notes)
        self.assertIn("native Android controller",notes)
        self.assertNotIn("Shizuku gains an on-device",notes)
        self.assertIn("0.16.3",notes)
        self.assertIn("Categorized",summary_for_version(source,"0.16.3"))

    def test_missing_or_invalid_version_fails_closed(self):
        sample="## v1.2.3 — Example\n\n- Changed A\n\n## v1.2.2 — Prior\n\n- Changed B\n"
        title, body=section_for_version(sample,"1.2.3")
        self.assertIn("Example",title)
        self.assertIn("Changed A",body)
        self.assertNotIn("Changed B",body)
        for version in ("1.2.4", "../1.2.3", "1.2.3;rm -rf /"):
            with self.assertRaises(ValueError):
                render_notes(sample,version)


if __name__ == "__main__":
    unittest.main()
