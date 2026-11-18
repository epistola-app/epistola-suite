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


def runtime_report(image: str) -> dict:
    return cve_watch.runtime_report(image, cve_watch.Runtime("BellSoft", "25.0.5.1", "25.0.5.1+1-LTS", None), [])


def scanned(image: str) -> list[dict]:
    """What a fully scanned image contributes: Trivy's report and the runtime report."""
    return [report(image), runtime_report(image)]


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


class ResolvedIssuesTest(unittest.TestCase):
    # On 2026-11-18 ten issues were open for CVEs filed against 1.4.0, 1.5.0 and 2.0.0-beta.1.
    # The supported images had moved on to 1.5.1 and 2.0.0 and scanned clean, but the watch only
    # ever opened issues, so they stayed open until closed by hand.
    SUPPORTED = ["ghcr/e:2.0.0", "ghcr/e:2.0.0-demo", "ghcr/e:1.5.1", "ghcr/e:1.5.1-demo"]

    def test_an_open_issue_no_supported_image_reports_is_resolved(self) -> None:
        reports = [r for image in self.SUPPORTED for r in scanned(image)]
        open_issues = [(1166, "[cve-watch] CVE-2026-45447 (HIGH) in libssl3t64, openssl")]
        resolved = cve_watch.resolved_issues(open_issues, cve_watch.collect_findings(reports))
        self.assertEqual(resolved, [(1166, "CVE-2026-45447")])

    def test_an_issue_still_reported_by_one_image_stays_open(self) -> None:
        reports = [report(self.SUPPORTED[0], vulnerability("CVE-1", "HIGH", "openssl", "3.0.13"))]
        reports += [report(image) for image in self.SUPPORTED[1:]]
        open_issues = [(1, "[cve-watch] CVE-1 (HIGH) in openssl"), (2, "[cve-watch] CVE-2 (HIGH) in gpgv")]
        resolved = cve_watch.resolved_issues(open_issues, cve_watch.collect_findings(reports))
        self.assertEqual(resolved, [(2, "CVE-2")])

    def test_issues_the_watch_did_not_open_are_left_alone(self) -> None:
        open_issues = [(9, "Critical vulnerabilities detected (1 critical, 0 high)")]
        self.assertEqual(cve_watch.resolved_issues(open_issues, {}), [])

    def test_an_image_without_a_report_blocks_closing(self) -> None:
        # Its scan failed. Nothing reported is not the same as nothing found.
        reports = [r for image in self.SUPPORTED[:3] for r in scanned(image)]
        self.assertEqual(cve_watch.missing_images(self.SUPPORTED, reports), ["ghcr/e:1.5.1-demo"])

    def test_an_image_whose_runtime_scan_failed_blocks_closing(self) -> None:
        # Trivy does not see the JRE, so without the runtime report a JRE issue would read as fixed.
        reports = [r for image in self.SUPPORTED for r in scanned(image)]
        reports = [r for r in reports if not (r["ArtifactName"] == "ghcr/e:2.0.0" and r.get("Scanner"))]
        self.assertEqual(cve_watch.missing_images(self.SUPPORTED, reports), ["ghcr/e:2.0.0 (Java runtime)"])

    def test_every_image_reported_means_nothing_is_missing(self) -> None:
        reports = [r for image in self.SUPPORTED for r in scanned(image)]
        self.assertEqual(cve_watch.missing_images(self.SUPPORTED, reports), [])

    def test_the_comment_names_the_images_scanned(self) -> None:
        comment = cve_watch.resolved_comment("CVE-1", self.SUPPORTED)
        self.assertIn("**CVE-1**", comment)
        self.assertIn("- `ghcr/e:1.5.1-demo`", comment)


# Trimmed from https://openjdk.org/groups/vulnerability/advisories/2026-11-17: the sentence naming
# the last affected version per line, the OpenJDK risk matrix, and the OpenJFX matrix after it,
# which is about a different product and must be ignored.
ADVISORY_2026_11_17 = """<h1>OpenJDK Vulnerability Advisory: 2026/11/17</h1><p>The following vulnerabilities in OpenJDK source code were fixed
in this release. The affected versions are 27.0.1.0.3, 25.0.5.0.3,
21.0.13.0.3, 17.0.21.0.3, 11.0.33, 8u512, and earlier. Please note
that defense-in-depth issues are not assigned CVEs.</p>
<h2 id="risk-matrix">OpenJDK Risk matrix</h2><table class="risk-matrix" summary="Risk matrix"><tr><th></th><th></th><th></th><th colspan="6">Affects ...</th></tr>
<tr><th nowrap="nowrap">CVE ID</th><th nowrap="nowrap">Component</th><th><a href="https://www.first.org/cvss/">CVSSv3.1</a><br />Vector</th><th>8</th><th>11</th><th>17</th><th>21</th><th>25</th><th>27</th></tr>
<tr><td nowrap="nowrap">CVE-2026-114938</td><td nowrap="nowrap">hotspot/<br />compiler</td><td>8.1<br />NHNNUHHH</td><td></td><td></td><td>&#8226;</td><td>&#8226;</td><td>&#8226;</td><td>&#8226;</td></tr>
<tr><td nowrap="nowrap"><a href="https://nvd.nist.gov/vuln/detail/CVE-2026-114939">CVE-2026-114939</a></td><td nowrap="nowrap">security-libs/<br />org.ietf.jgss</td><td>7.4<br />NHNNUHHN</td><td>&#8226;</td><td>&#8226;</td><td>&#8226;</td><td>&#8226;</td><td>&#8226;</td><td>&#8226;</td></tr>
<tr><td nowrap="nowrap">CVE-2026-114943</td><td nowrap="nowrap">hotspot/<br />runtime</td><td>6.5<br />NHNNUNLH</td><td>&#8226;</td><td>&#8226;</td><td>&#8226;</td><td>&#8226;</td><td>&#8226;</td><td>&#8226;</td></tr>
<tr><td nowrap="nowrap">CVE-2026-990001</td><td nowrap="nowrap">client-libs/<br />2d</td><td>9.0<br />NLNNUHHH</td><td>&#8226;</td><td>&#8226;</td><td></td><td></td><td></td><td></td></tr>
</table>
<h2>OpenJFX Risk matrix</h2><table class="risk-matrix" summary="Risk matrix"><tr><th></th><th></th><th></th><th colspan="3">Affects ...</th></tr>
<tr><th nowrap="nowrap">CVE ID</th><th nowrap="nowrap">Component</th><th>CVSSv3.1<br />Vector</th><th>21</th><th>25</th><th>27</th></tr>
<tr><td nowrap="nowrap">CVE-2026-86144</td><td nowrap="nowrap">javafx/<br />web</td><td>7.5<br />NHNRUHHH</td><td>&#8226;</td><td>&#8226;</td><td>&#8226;</td></tr></table>"""

ADVISORY_URL = "https://openjdk.org/groups/vulnerability/advisories/2026-11-17"

# /layers/paketo-buildpacks_bellsoft-liberica/jre/release in ghcr.io/epistola-app/epistola-suite:2.0.0
RELEASE_2_0_0 = """IMPLEMENTOR="BellSoft"
JAVA_RUNTIME_VERSION="25.0.5.0.3+5-LTS"
JAVA_VERSION="25.0.5.0.3"
JAVA_VERSION_DATE="2026-10-24"
OS_ARCH="x86_64"
"""


class RuntimeScanTest(unittest.TestCase):
    # CVE-2026-114938 (Java4Shell) was disclosed on 2026-11-17 against the Liberica JRE that 2.0.0
    # and 1.5.1 ship. Trivy never reported it: a buildpack installs the JRE as a plain directory,
    # neither an OS package nor a jar. A maintainer had to report it.

    def test_the_runtime_is_read_from_the_release_file(self) -> None:
        runtime = cve_watch.parse_runtime_release(RELEASE_2_0_0)
        self.assertEqual(runtime, cve_watch.Runtime("BellSoft", "25.0.5.0.3", "25.0.5.0.3+5-LTS", date(2026, 10, 24)))
        self.assertEqual(runtime.feature, 25)

    def test_versions_compare_numerically_across_lengths(self) -> None:
        key = cve_watch.java_version_key
        self.assertLess(key("25.0.5"), key("25.0.5.0.3"))
        self.assertLess(key("25.0.5.0.3"), key("25.0.5.1"))
        self.assertLess(key("25.0.4.1"), key("25.0.5"))
        self.assertEqual(key("8u512"), key("8.0.512"))

    def test_an_advisory_names_the_last_affected_version_per_line_and_its_cves(self) -> None:
        advisory = cve_watch.parse_advisory(ADVISORY_2026_11_17, ADVISORY_URL)
        self.assertEqual(advisory.last_affected[25], "25.0.5.0.3")
        self.assertEqual(advisory.last_affected[8], "8u512")
        cves = {cve.identifier: cve for cve in advisory.cves}
        self.assertNotIn("CVE-2026-86144", cves, "the OpenJFX matrix is a different product")
        self.assertEqual(cves["CVE-2026-114938"].score, 8.1)
        self.assertEqual(cves["CVE-2026-114938"].component, "hotspot/compiler")
        self.assertEqual(cves["CVE-2026-114938"].features, {17, 21, 25, 27})

    def test_the_jre_in_2_0_0_is_affected_by_java4shell(self) -> None:
        runtime = cve_watch.parse_runtime_release(RELEASE_2_0_0)
        advisory = cve_watch.parse_advisory(ADVISORY_2026_11_17, ADVISORY_URL)
        found = {v["VulnerabilityID"]: v for v in cve_watch.runtime_vulnerabilities(runtime, [advisory])}
        # 114943 scores 6.5 (below HIGH, like the image scan's severity filter); 990001 is not on 25.
        self.assertEqual(set(found), {"CVE-2026-114938", "CVE-2026-114939"})
        java4shell = found["CVE-2026-114938"]
        self.assertEqual(java4shell["Severity"], "HIGH")
        self.assertEqual(java4shell["InstalledVersion"], "25.0.5.0.3+5-LTS")
        self.assertEqual(java4shell["FixedVersion"], "later than 25.0.5.0.3")
        self.assertEqual(java4shell["PrimaryURL"], ADVISORY_URL)

    def test_a_jre_newer_than_the_last_affected_version_is_not_affected(self) -> None:
        runtime = cve_watch.Runtime("BellSoft", "25.0.5.1", "25.0.5.1+1-LTS", date(2026, 11, 20))
        advisory = cve_watch.parse_advisory(ADVISORY_2026_11_17, ADVISORY_URL)
        self.assertEqual(cve_watch.runtime_vulnerabilities(runtime, [advisory]), [])

    def test_only_advisories_from_the_runtime_date_on_are_needed(self) -> None:
        index = '<a href="2026-11-17">x</a><a href="2026-10-24">y</a><a href="2026-10-21">z</a><a href="advisory-key">k</a>'
        self.assertEqual(
            cve_watch.advisory_urls(index, since=date(2026, 10, 24)),
            [ADVISORY_URL, "https://openjdk.org/groups/vulnerability/advisories/2026-10-24"],
        )

    def test_the_runtime_report_feeds_triage_like_a_trivy_report(self) -> None:
        runtime = cve_watch.parse_runtime_release(RELEASE_2_0_0)
        advisory = cve_watch.parse_advisory(ADVISORY_2026_11_17, ADVISORY_URL)
        image = "ghcr.io/epistola-app/epistola-suite:2.0.0"
        runtime_scan = cve_watch.runtime_report(image, runtime, cve_watch.runtime_vulnerabilities(runtime, [advisory]))
        findings = cve_watch.collect_findings([report(image), runtime_scan])
        self.assertEqual(
            cve_watch.issue_title(findings["CVE-2026-114938"]),
            "[cve-watch] CVE-2026-114938 (HIGH) in java-runtime",
        )
        self.assertEqual(findings["CVE-2026-114938"].images, {image})

    def test_a_not_affected_assessment_in_our_vex_suppresses_a_runtime_finding(self) -> None:
        runtime = cve_watch.parse_runtime_release(RELEASE_2_0_0)
        advisory = cve_watch.parse_advisory(ADVISORY_2026_11_17, ADVISORY_URL)
        vex = {
            "statements": [
                {"vulnerability": {"name": "CVE-2026-114939"}, "status": "not_affected",
                 "products": [{"@id": "pkg:generic/bellsoft-jre@25.0.5?arch=amd64"}]},
                {"vulnerability": {"name": "CVE-2026-114938"}, "status": "affected",
                 "products": [{"@id": "pkg:generic/bellsoft-jre@25.0.5?arch=amd64"}]},
            ]
        }
        found = cve_watch.runtime_vulnerabilities(runtime, [advisory], cve_watch.vex_suppressed(vex))
        self.assertEqual([v["VulnerabilityID"] for v in found], ["CVE-2026-114938"])


if __name__ == "__main__":
    unittest.main()
