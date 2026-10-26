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
    def test_latest_patch_of_the_two_newest_minor_lines(self) -> None:
        tags = ["v1.3.0", "v1.4.0", "v1.4.2", "v1.4.10", "v1.5.0", "v1.5.1"]
        self.assertEqual(cve_watch.supported_versions(tags), ["1.5.1", "1.4.10"])

    def test_pre_releases_and_other_tags_are_not_releases(self) -> None:
        tags = ["v2.0.0-RC1", "epistola-1.1.0", "v1.5.0", "v1.4.0", "1.9.0"]
        self.assertEqual(cve_watch.supported_versions(tags), ["1.5.0", "1.4.0"])

    def test_a_new_major_counts_as_the_newest_line(self) -> None:
        self.assertEqual(cve_watch.supported_versions(["v1.5.0", "v2.0.0", "v1.4.0"]), ["2.0.0", "1.5.0"])

    def test_a_single_line_is_all_there_is(self) -> None:
        self.assertEqual(cve_watch.supported_versions(["v1.0.0", "v1.0.1"]), ["1.0.1"])

    def test_both_variants_of_each_version(self) -> None:
        self.assertEqual(
            cve_watch.image_refs("o/r", ["1.5.0"]),
            ["ghcr.io/o/r:1.5.0", "ghcr.io/o/r:1.5.0-demo"],
        )


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
