#!/usr/bin/env python3
# SPDX-FileCopyrightText: Epistola Nederland B.V.
#
# SPDX-License-Identifier: AGPL-3.0-only

"""Regression tests for the agent instruction checker.

Every case here is a rule that was wrong at some point while the checker was being written, and
each wrong version produced confident, plausible output: globs read as missing classes, a whole
repository pruned because it sat under a directory called `worktrees`, and an index format that
parsed one of the two tables in the repository. A checker that reports false problems is worse
than none, because the natural response is to "fix" the files it accuses.

They run through ``pnpm agents:check``, before the checker itself.
"""

from __future__ import annotations

import sys
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory

sys.path.insert(0, str(Path(__file__).resolve().parent))

import agents_check


class CodeTextTest(unittest.TestCase):
    """Symbols are read from code, and anything holding a `*` is a pattern, not a name."""

    def test_a_glob_span_is_not_a_symbol(self) -> None:
        text = "Prefer a `*HandlerHtmxTest` over a browser test."

        self.assertNotIn("HandlerHtmxTest", agents_check.code_text(text))

    def test_a_glob_inside_a_fenced_block_is_dropped(self) -> None:
        text = '```bash\npnpm build\n./gradlew test --tests "*YourTest*"\n```'

        code = agents_check.code_text(text)

        self.assertNotIn("YourTest", code)
        self.assertIn("pnpm build", code, "the rest of the block still counts")

    def test_prose_is_not_read_as_code(self) -> None:
        text = "`modules/design-system` is a pnpm package, not a Gradle project."

        self.assertEqual([], agents_check.PNPM_SCRIPT.findall(agents_check.code_text(text)))

    def test_a_real_symbol_survives(self) -> None:
        text = "Enforced by `MediatorWiringTest`."

        self.assertIn("MediatorWiringTest", agents_check.code_text(text))


class WalkTest(unittest.TestCase):
    def test_pruning_is_relative_to_the_root(self) -> None:
        """A checkout under a pruned name must not prune itself.

        Git worktrees live under a `worktrees/` directory, so matching pruned names against the
        absolute path silently yielded an empty repository — and every referenced symbol then
        looked missing.
        """
        with TemporaryDirectory() as tmp:
            root = Path(tmp) / "worktrees" / "checkout"
            (root / "src").mkdir(parents=True)
            (root / "src" / "Thing.kt").write_text("x", encoding="utf-8")
            (root / "build").mkdir()
            (root / "build" / "Generated.kt").write_text("x", encoding="utf-8")

            found = {path.name for path in agents_check.walk(root)}

            self.assertIn("Thing.kt", found)
            self.assertNotIn("Generated.kt", found, "build output is still pruned")


class IndexEntriesTest(unittest.TestCase):
    def test_reads_both_index_formats(self) -> None:
        """The docs index links the page alone; the ADR index adds a title after the link."""
        with TemporaryDirectory() as tmp:
            index = Path(tmp) / "README.md"
            index.write_text(
                "| Doc | Status | What |\n"
                "| --- | --- | --- |\n"
                "| [Project overview](epistola.md) | Current | What Epistola is. |\n"
                "| [0001](0001-stencil-placeholders.md) Stencil placeholders | Accepted | A note. |\n"
                "| [External](https://example.test/x) | Current | Skipped. |\n",
                encoding="utf-8",
            )

            entries = agents_check.index_entries(index)

            self.assertEqual("Current", entries["epistola.md"])
            self.assertEqual("Accepted", entries["0001-stencil-placeholders.md"])
            self.assertNotIn("x", entries, "external links are not repository pages")

    def test_a_missing_index_is_empty_rather_than_fatal(self) -> None:
        self.assertEqual({}, agents_check.index_entries(Path("/nonexistent/README.md")))


class VerifyHeadingTest(unittest.TestCase):
    def test_accepts_the_shapes_skills_actually_use(self) -> None:
        for heading in ("## Verify", "### 5. Verify", "## 4. Verification commands"):
            with self.subTest(heading=heading):
                self.assertTrue(agents_check.VERIFY_HEADING.search(heading + "\n"))

    def test_rejects_a_skill_with_no_verify_section(self) -> None:
        self.assertIsNone(agents_check.VERIFY_HEADING.search("## Response\n\nLead with the cause.\n"))


class SymlinkTest(unittest.TestCase):
    def test_reports_missing_copied_and_correct_links(self) -> None:
        with TemporaryDirectory() as tmp:
            root = Path(tmp)
            target = root / "card.md"
            target.write_text("x", encoding="utf-8")

            missing = root / "missing.md"
            self.assertIn("is missing", agents_check.check_symlink(missing, target)[0].message)

            copy = root / "copy.md"
            copy.write_text("x", encoding="utf-8")
            self.assertIn("is a copy", agents_check.check_symlink(copy, target)[0].message)

            link = root / "link.md"
            link.symlink_to(target)
            self.assertEqual([], agents_check.check_symlink(link, target))


if __name__ == "__main__":
    unittest.main()
