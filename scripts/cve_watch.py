#!/usr/bin/env python3
# SPDX-FileCopyrightText: Epistola Nederland B.V.
#
# SPDX-License-Identifier: AGPL-3.0-only

"""Daily CVE watch over the container images customers actually run.

The ``Security Scan`` workflow already scans the SBOMs of ``main``. Those cover the
Java and npm dependencies only, and only as of the latest commit: a CVE in an
operating-system package of a *released* image (CVE-2026-111497 in the base
image's ``libbrotli1``, for example) is invisible to them. This script closes that
gap in two steps:

``supported-images``
    Prints a JSON list of the image references for every release line we maintain.
    A line is maintained while it has a branch: ``main`` and each ``release/<major>.x``
    maintenance branch. Per branch it takes the newest release tagged on it, plus the newest
    pre-release when that is newer (a major in alpha or beta has no release yet),
    each in its production and ``-demo`` variant. The workflow scans each one with
    Trivy, applying our own OpenVEX document.

``triage``
    Reads the Trivy JSON reports, groups the findings per vulnerability, and opens
    one ``cve-watch`` issue for each vulnerability that has never had one. Closing
    the issue is the triage decision; a closed issue is never reopened. A finding
    assessed ``not_affected`` belongs in a ``kind: dependency`` record instead, so
    the VEX suppresses it at scan time.

Whether a finding is *exploitable* is not something a scanner can answer. The
issue carries the evidence and points at docs/sbom.md#assessing-a-scanner-finding,
where the assessment procedure lives.
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

LABEL = "cve-watch"
TITLE_PREFIX = "[cve-watch]"
VERSION_TAG = re.compile(r"^v(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z]+(?:\.[0-9A-Za-z]+)*))?$")
TITLE_ID = re.compile(r"^\[cve-watch\] (\S+)")
SEVERITY_ORDER = ["UNKNOWN", "LOW", "MEDIUM", "HIGH", "CRITICAL"]
IMAGE_VARIANTS = ["", "-demo"]
REMOTE = "refs/remotes/origin/"
# A maintenance branch is `release/1.x` (or `release/2.0`). The other `release/*` branches
# are left over from preparing one release (`release/v1.1.0`, `release/grafana-0.1.1`).
MAINTAINED_BRANCH = re.compile(r"^(main|release/\d+\.(\d+|x))$")


@dataclass(frozen=True)
class Version:
    major: int
    minor: int
    patch: int
    pre_release: str  # "" for a release

    @staticmethod
    def parse(tag: str) -> Version | None:
        match = VERSION_TAG.match(tag.strip())
        if not match:
            return None
        return Version(int(match[1]), int(match[2]), int(match[3]), match[4] or "")

    def precedence(self) -> tuple[Any, ...]:
        """SemVer order: a release outranks its pre-releases, numeric identifiers compare as numbers."""
        if not self.pre_release:
            return (self.major, self.minor, self.patch, 1, ())
        identifiers = tuple(
            (0, int(part), "") if part.isdigit() else (1, 0, part) for part in self.pre_release.split(".")
        )
        return (self.major, self.minor, self.patch, 0, identifiers)

    def __str__(self) -> str:
        suffix = f"-{self.pre_release}" if self.pre_release else ""
        return f"{self.major}.{self.minor}.{self.patch}{suffix}"


def branch_versions(tags: list[str]) -> list[Version]:
    """What customers of one branch's line run: its newest release, and a newer pre-release.

    ``tags`` are the tags reachable from the branch. The line is the major of the
    newest of them, so ``main`` stops counting the 1.x tags in its history once it
    carries a 2.0 pre-release. Within that major, the newest release is what is
    deployed; a pre-release newer than it (the next minor's RC, or every alpha of
    a major with no release yet) is what is being tried out.
    """
    versions = [version for tag in tags if (version := Version.parse(tag))]
    if not versions:
        return []
    major = max(versions, key=Version.precedence).major
    in_line = [version for version in versions if version.major == major]
    releases = [version for version in in_line if not version.pre_release]
    pre_releases = [version for version in in_line if version.pre_release]
    picked: list[Version] = []
    newest_release = max(releases, key=Version.precedence) if releases else None
    if newest_release:
        picked.append(newest_release)
    if pre_releases:
        newest_pre_release = max(pre_releases, key=Version.precedence)
        if newest_release is None or newest_pre_release.precedence() > newest_release.precedence():
            picked.append(newest_pre_release)
    return picked


def supported_versions(tags_per_branch: dict[str, list[str]]) -> list[str]:
    """The versions to scan across every maintained branch, newest first and each once."""
    versions = {version for tags in tags_per_branch.values() for version in branch_versions(tags)}
    return [str(version) for version in sorted(versions, key=Version.precedence, reverse=True)]


def git(*args: str) -> str:
    return subprocess.run(["git", *args], check=True, capture_output=True, text=True).stdout


def is_maintained(branch: str) -> bool:
    return MAINTAINED_BRANCH.match(branch) is not None


def maintained_branch_tags() -> dict[str, list[str]]:
    refs = git("for-each-ref", "--format=%(refname)", f"{REMOTE}main", f"{REMOTE}release/").split()
    branches = [ref.removeprefix(REMOTE) for ref in refs if is_maintained(ref.removeprefix(REMOTE))]
    return {branch: git("tag", "--list", "v*", "--merged", REMOTE + branch).split() for branch in branches}


def image_refs(repository: str, versions: list[str]) -> list[str]:
    return [f"ghcr.io/{repository}:{version}{suffix}" for version in versions for suffix in IMAGE_VARIANTS]


@dataclass
class Finding:
    """One vulnerability, merged across every package and image it was reported in."""

    identifier: str
    severity: str
    title: str
    url: str
    # (package, installed version, fixed version or "") per affected package
    packages: set[tuple[str, str, str]] = field(default_factory=set)
    images: set[str] = field(default_factory=set)


def collect_findings(reports: list[dict[str, Any]]) -> dict[str, Finding]:
    findings: dict[str, Finding] = {}
    for report in reports:
        image = report.get("ArtifactName", "unknown image")
        for result in report.get("Results") or []:
            for vulnerability in result.get("Vulnerabilities") or []:
                identifier = vulnerability["VulnerabilityID"]
                severity = vulnerability.get("Severity", "UNKNOWN")
                finding = findings.get(identifier)
                if finding is None:
                    finding = Finding(
                        identifier=identifier,
                        severity=severity,
                        title=vulnerability.get("Title", "").strip(),
                        url=vulnerability.get("PrimaryURL", ""),
                    )
                    findings[identifier] = finding
                elif SEVERITY_ORDER.index(severity) > SEVERITY_ORDER.index(finding.severity):
                    finding.severity = severity
                finding.packages.add(
                    (
                        vulnerability.get("PkgName", "?"),
                        vulnerability.get("InstalledVersion", "?"),
                        vulnerability.get("FixedVersion", ""),
                    )
                )
                finding.images.add(image)
    return findings


def known_identifiers(issue_titles: list[str]) -> set[str]:
    """Vulnerability ids that already have an issue, open or closed."""
    return {match[1] for title in issue_titles if (match := TITLE_ID.match(title))}


def issue_title(finding: Finding) -> str:
    packages = ", ".join(sorted({name for name, _, _ in finding.packages}))
    return f"{TITLE_PREFIX} {finding.identifier} ({finding.severity}) in {packages}"


def issue_body(finding: Finding, repository: str) -> str:
    rows = "\n".join(
        f"| `{name}` | `{installed}` | {f'`{fixed}`' if fixed else 'no fix yet'} |"
        for name, installed, fixed in sorted(finding.packages)
    )
    images = "\n".join(f"- `{image}`" for image in sorted(finding.images))
    reference = f"\n\nUpstream: {finding.url}" if finding.url else ""
    return f"""The daily CVE watch found **{finding.identifier}** ({finding.severity}) in a supported release image.

{finding.title}{reference}

| Package | Installed | Fixed in |
| ------- | --------- | -------- |
{rows}

**Images**

{images}

## Triage

Follow [Assessing a scanner finding](https://github.com/{repository}/blob/main/docs/sbom.md#assessing-a-scanner-finding):

- [ ] Read the upstream advisory and its preconditions, not the scanner summary.
- [ ] Establish reachability in this codebase and the image, and record the evidence here.
- [ ] Prefer an upgrade (dependency bump, or a rebuilt image on a patched base) over arguing.
- [ ] Not affected and suppressing it, or knowingly shipping it: add a `kind: dependency` record under `vulnerabilities/`.

Close this issue once it is triaged. The watch never reopens or re-files a closed issue.
"""


def gh(*args: str) -> str:
    return subprocess.run(["gh", *args], check=True, capture_output=True, text=True).stdout


def existing_issue_titles() -> list[str]:
    output = gh("issue", "list", "--label", LABEL, "--state", "all", "--limit", "5000", "--json", "title")
    return [issue["title"] for issue in json.loads(output)]


def triage(report_paths: list[Path], repository: str, dry_run: bool, max_new: int) -> int:
    reports = [json.loads(path.read_text(encoding="utf-8")) for path in report_paths]
    findings = collect_findings(reports)
    if dry_run:
        known: set[str] = set()
    else:
        gh("label", "create", LABEL, "--force", "--color", "b60205",
           "--description", "Opened by the daily CVE watch over supported release images")
        known = known_identifiers(existing_issue_titles())

    new = sorted(
        (finding for identifier, finding in findings.items() if identifier not in known),
        key=lambda finding: (-SEVERITY_ORDER.index(finding.severity), finding.identifier),
    )
    print(f"{len(findings)} vulnerabilities in {len(reports)} images; {len(new)} without an issue.")
    for finding in new[:max_new]:
        title = issue_title(finding)
        if dry_run:
            print(f"would open: {title}")
            continue
        url = gh("issue", "create", "--label", LABEL, "--title", title, "--body", issue_body(finding, repository)).strip()
        print(f"opened: {title} {url}")
    if len(new) > max_new:
        # The cap keeps a first run, or a Trivy database hiccup, from flooding the
        # tracker. The rest are filed on the next run, highest severity first.
        print(f"{len(new) - max_new} more left for the next run (--max-new {max_new}).")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest="command", required=True)

    images = commands.add_parser("supported-images", help="print the supported release images as JSON")
    images.add_argument("--repository", required=True, help="owner/name, e.g. epistola-app/epistola-suite")

    triage_parser = commands.add_parser("triage", help="open an issue per new vulnerability")
    triage_parser.add_argument("reports", nargs="+", type=Path, help="Trivy JSON reports")
    triage_parser.add_argument("--repository", required=True, help="owner/name, e.g. epistola-app/epistola-suite")
    triage_parser.add_argument("--dry-run", action="store_true", help="print, do not touch GitHub")
    triage_parser.add_argument("--max-new", type=int, default=20)

    args = parser.parse_args(argv)
    if args.command == "supported-images":
        tags_per_branch = maintained_branch_tags()
        versions = supported_versions(tags_per_branch)
        if not versions:
            print("no release tags on main or release/*; fetch branches and tags first", file=sys.stderr)
            return 1
        for branch, tags in sorted(tags_per_branch.items()):
            picked = ", ".join(map(str, branch_versions(tags))) or "nothing tagged"
            print(f"{branch}: {picked}", file=sys.stderr)
        print(json.dumps(image_refs(args.repository, versions)))
        return 0
    return triage(args.reports, args.repository, args.dry_run, args.max_new)


if __name__ == "__main__":
    sys.exit(main())
