#!/usr/bin/env python3
# SPDX-FileCopyrightText: Epistola Nederland B.V.
#
# SPDX-License-Identifier: AGPL-3.0-only

"""Regression tests for the daily CVE watch in ``cve_watch.py``.

They run through ``pnpm vulnerabilities:check``. The GitHub side (``gh``) is not
exercised here; ``triage --dry-run`` covers the path up to it.
"""

from __future__ import annotations

import sys
import unittest
from datetime import date
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import cve_watch


def vulnerability(identifier: str, severity: str, package: str, installed: str, fixed: str = "") -> dict:
    return {
        "VulnerabilityID": identifier,
        "Severity": severity,
        "PkgName": package,
        "InstalledVersion": installed,
        "FixedVersion": fixed,
        "Title": f"{package}: something bad",
        "PrimaryURL": f"https://avd.aquasec.com/nvd/{identifier.lower()}",
    }


def report(image: str, *vulnerabilities: dict) -> dict:
    return {"ArtifactName": image, "Results": [{"Target": "os", "Vulnerabilities": list(vulnerabilities)}]}


class SupportedVersionsTest(unittest.TestCase):
    def test_the_newest_release_of_the_branch(self) -> None:
        tags = ["v1.3.0", "v1.4.0", "v1.4.2", "v1.4.10", "v1.5.0", "v1.5.1"]
        self.assertEqual([str(v) for v in cve_watch.branch_versions(tags)], ["1.5.1"])

    def test_a_major_with_only_pre_releases_scans_the_newest_one(self) -> None:
        # main on 2026-11-05: the whole 1.x history is reachable, but main is the 2.0 line.
        tags = ["v1.4.0", "v1.5.0", "v2.0.0-alpha.1", "v2.0.0-beta.1", "v2.0.0-alpha.2"]
        self.assertEqual([str(v) for v in cve_watch.branch_versions(tags)], ["2.0.0-beta.1"])

    def test_a_pre_release_newer_than_the_release_is_scanned_too(self) -> None:
        tags = ["v2.0.0-beta.1", "v2.0.0", "v2.0.3", "v2.1.0-RC.1"]
        self.assertEqual([str(v) for v in cve_watch.branch_versions(tags)], ["2.0.3", "2.1.0-RC.1"])

    def test_a_pre_release_older_than_the_release_is_not(self) -> None:
        tags = ["v2.0.0-RC.2", "v2.0.0"]
        self.assertEqual([str(v) for v in cve_watch.branch_versions(tags)], ["2.0.0"])

    def test_pre_release_identifiers_compare_numerically(self) -> None:
        tags = ["v2.0.0-alpha.9", "v2.0.0-alpha.10"]
        self.assertEqual([str(v) for v in cve_watch.branch_versions(tags)], ["2.0.0-alpha.10"])

    def test_other_tags_are_not_versions(self) -> None:
        tags = ["epistola-1.1.0", "1.9.0", "v1.5", "v1.5.0"]
        self.assertEqual([str(v) for v in cve_watch.branch_versions(tags)], ["1.5.0"])

    def test_an_untagged_branch_scans_nothing(self) -> None:
        self.assertEqual(cve_watch.branch_versions([]), [])

    def test_every_maintained_branch_once_newest_first(self) -> None:
        tags_per_branch = {
            "main": ["v1.4.0", "v1.5.0", "v2.0.0", "v2.1.0-RC.1"],
            # cut from main at 2.0.0, so both branches reach that tag
            "release/2.x": ["v1.4.0", "v1.5.0", "v2.0.0"],
            "release/1.x": ["v1.4.0", "v1.5.0", "v1.5.1"],
        }
        self.assertEqual(cve_watch.supported_versions(tags_per_branch), ["2.1.0-RC.1", "2.0.0", "1.5.1"])

    def test_only_main_and_maintenance_branches_are_lines(self) -> None:
        maintained = ["main", "release/1.x", "release/2.0", "release/10.x"]
        leftovers = ["release/v1.1.0", "release/grafana-0.1.1", "release/1.0.0-rc5", "release/x", "mainline"]
        self.assertEqual([b for b in maintained + leftovers if cve_watch.is_maintained(b)], maintained)

    def test_both_variants_of_each_version(self) -> None:
        self.assertEqual(
            cve_watch.image_refs("o/r", ["1.5.0"]),
            ["ghcr.io/o/r:1.5.0", "ghcr.io/o/r:1.5.0-demo"],
        )


# The 1.x releases as they shipped, and a 2.0.0 that ships on 10 January 2027.
RELEASES_1X = {
    "v1.0.0-RC6": date(2026, 7, 20),
    "v1.0.0": date(2026, 7, 31),
    "v1.1.0": date(2026, 8, 25),
    "v1.2.0": date(2026, 9, 22),
    "v1.3.0": date(2026, 9, 28),
    "v1.4.0": date(2026, 10, 13),
    "v1.5.0": date(2026, 10, 15),
    "v2.0.0-beta.1": date(2026, 11, 4),
}
RELEASES_2_0 = {**RELEASES_1X, "v2.0.0": date(2027, 1, 10)}
BRANCHES_TODAY = {"main": ["v1.5.0", "v2.0.0-beta.1"], "release/1.x": ["v1.5.0"]}
BRANCHES_2_0 = {"main": ["v1.5.0", "v2.0.0"], "release/1.x": ["v1.5.0", "v1.5.1"]}


def problems(support, today, releases=RELEASES_1X, branches=BRANCHES_TODAY):
    return cve_watch.support_problems(support, releases, branches, today)


class SupportCheckTest(unittest.TestCase):
    def test_the_floor_published_on_2026_11_05_is_four_lines_too_low(self) -> None:
        [problem] = problems({"minVersion": "1.0.0"}, date(2026, 11, 5))
        self.assertIn("still supports 1.0", problem)
        self.assertIn("Raise support.minVersion to 1.4.0 with support.until 2026-11-14", problem)

    def test_the_previous_minor_has_30_days_after_the_newest_ships(self) -> None:
        self.assertEqual(problems({"minVersion": "1.4.0", "until": "2026-11-14"}, date(2026, 11, 5)), [])

    def test_its_end_date_is_announced(self) -> None:
        [problem] = problems({"minVersion": "1.4.0"}, date(2026, 11, 5))
        self.assertIn("announces no support.until. Set support.until to 2026-11-14", problem)

    def test_a_line_two_minors_back_ends_when_the_second_ships(self) -> None:
        # 1.3 had 30 days from 1.4.0 (13 October), but 1.5.0 two days later ended it.
        self.assertEqual(cve_watch.policy_ends(cve_watch.line_starts(RELEASES_1X))[(1, 3)], date(2026, 10, 15))

    def test_after_the_grace_period_the_floor_moves_up(self) -> None:
        [problem] = problems({"minVersion": "1.4.0", "until": "2026-11-14"}, date(2026, 11, 15))
        self.assertIn("still supports 1.4", problem)
        self.assertIn("Raise support.minVersion to 1.5.0.", problem)

    def test_a_line_is_not_dropped_before_its_end(self) -> None:
        [problem] = problems({"minVersion": "1.5.0"}, date(2026, 11, 5))
        self.assertIn("drops 1.4", problem)
        self.assertIn("until 2026-11-14", problem)

    def test_the_last_minor_of_a_major_has_three_months_after_the_next_major(self) -> None:
        support = {"minVersion": "1.5.0", "until": "2027-04-10"}
        self.assertEqual(problems(support, date(2027, 1, 11), RELEASES_2_0, BRANCHES_2_0), [])
        [problem] = problems({"minVersion": "1.5.0"}, date(2027, 1, 11), RELEASES_2_0, BRANCHES_2_0)
        self.assertIn("Set support.until to 2027-04-10", problem)

    def test_an_end_date_is_never_brought_forward(self) -> None:
        support = {"minVersion": "1.5.0", "until": "2027-03-01"}
        [problem] = problems(support, date(2027, 1, 11), RELEASES_2_0, BRANCHES_2_0)
        self.assertIn("publishes support.until 2027-03-01", problem)

    def test_an_extension_is_allowed(self) -> None:
        support = {"minVersion": "1.5.0", "until": "2027-06-30"}
        self.assertEqual(problems(support, date(2027, 5, 1), RELEASES_2_0, BRANCHES_2_0), [])

    def test_a_supported_major_needs_a_branch_to_patch_it_from(self) -> None:
        support = {"minVersion": "1.5.0", "until": "2027-04-10"}
        [problem] = problems(support, date(2027, 1, 11), RELEASES_2_0, {"main": ["v2.0.0"]})
        self.assertIn("no branch maintains it: cut release/1.x", problem)

    def test_a_branch_for_a_line_nobody_is_promised_is_flagged(self) -> None:
        [problem] = problems({"minVersion": "2.0.0"}, date(2027, 4, 11), RELEASES_2_0, BRANCHES_2_0)
        self.assertIn("release/1.x maintains 1.x, which releases.json no longer supports", problem)

    def test_no_support_block_is_a_problem(self) -> None:
        [problem] = problems(None, date(2026, 11, 5))
        self.assertIn("no support.minVersion; the policy's floor is 1.4.0", problem)

    def test_months_are_calendar_months(self) -> None:
        self.assertEqual(cve_watch.add_months(date(2027, 11, 30), 3), date(2028, 2, 29))
        self.assertEqual(cve_watch.add_months(date(2027, 1, 10), 3), date(2027, 4, 10))


class CollectFindingsTest(unittest.TestCase):
    def test_one_finding_per_vulnerability_across_images_and_packages(self) -> None:
        reports = [
            report(
                "ghcr.io/o/r:1.5.0",
                vulnerability("CVE-1", "HIGH", "openssl", "3.0.13", "3.0.13.1"),
                vulnerability("CVE-1", "HIGH", "libssl3t64", "3.0.13", "3.0.13.1"),
            ),
            report("ghcr.io/o/r:1.4.0", vulnerability("CVE-1", "CRITICAL", "openssl", "3.0.13", "3.0.13.1")),
        ]
        findings = cve_watch.collect_findings(reports)

        self.assertEqual(list(findings), ["CVE-1"])
        finding = findings["CVE-1"]
        self.assertEqual(finding.severity, "CRITICAL", "the highest severity any report gives wins")
        self.assertEqual(finding.images, {"ghcr.io/o/r:1.5.0", "ghcr.io/o/r:1.4.0"})
        self.assertEqual({name for name, _, _ in finding.packages}, {"openssl", "libssl3t64"})

    def test_a_clean_report_has_no_vulnerabilities_key(self) -> None:
        # Trivy omits `Vulnerabilities` (and sometimes `Results`) when nothing matched.
        clean = [{"ArtifactName": "x", "Results": [{"Target": "os"}]}, {"ArtifactName": "y"}]
        self.assertEqual(cve_watch.collect_findings(clean), {})


class IssueTest(unittest.TestCase):
    def test_an_issue_open_or_closed_marks_the_vulnerability_known(self) -> None:
        titles = [
            "[cve-watch] CVE-2026-1 (HIGH) in openssl",
            "[cve-watch] GHSA-abcd-efgh-ijkl (CRITICAL) in stdlib",
            "Critical vulnerabilities detected (1 critical, 0 high)",
        ]
        self.assertEqual(cve_watch.known_identifiers(titles), {"CVE-2026-1", "GHSA-abcd-efgh-ijkl"})

    def test_the_title_round_trips_through_known_identifiers(self) -> None:
        findings = cve_watch.collect_findings([report("i", vulnerability("CVE-9", "HIGH", "libbrotli1", "1.1.0"))])
        title = cve_watch.issue_title(findings["CVE-9"])
        self.assertEqual(title, "[cve-watch] CVE-9 (HIGH) in libbrotli1")
        self.assertEqual(cve_watch.known_identifiers([title]), {"CVE-9"})

    def test_body_says_when_there_is_no_fix(self) -> None:
        findings = cve_watch.collect_findings([report("img:1", vulnerability("CVE-9", "HIGH", "libbrotli1", "1.1.0"))])
        body = cve_watch.issue_body(findings["CVE-9"], "o/r")
        self.assertIn("| `libbrotli1` | `1.1.0` | no fix yet |", body)
        self.assertIn("- `img:1`", body)
        self.assertIn("https://github.com/o/r/blob/main/docs/sbom.md#assessing-a-scanner-finding", body)


if __name__ == "__main__":
    unittest.main()
