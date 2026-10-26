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
    Prints a JSON list of the image references for every supported release line,
    per SUPPORT_POLICY.md: the latest patch of the two most recent minor lines,
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
RELEASE_TAG = re.compile(r"^v(\d+)\.(\d+)\.(\d+)$")
TITLE_ID = re.compile(r"^\[cve-watch\] (\S+)")
SEVERITY_ORDER = ["UNKNOWN", "LOW", "MEDIUM", "HIGH", "CRITICAL"]
SUPPORTED_MINOR_LINES = 2
IMAGE_VARIANTS = ["", "-demo"]


def supported_versions(tags: list[str], lines: int = SUPPORTED_MINOR_LINES) -> list[str]:
    """Latest patch of the newest ``lines`` minor lines, newest first.

    Pre-release tags (``v1.0.0-RC3``) are not releases anyone is supported on, so
    the strict pattern leaves them out.
    """
    latest: dict[tuple[int, int], tuple[int, int, int]] = {}
    for tag in tags:
        match = RELEASE_TAG.match(tag.strip())
        if not match:
            continue
        version = (int(match[1]), int(match[2]), int(match[3]))
        line = version[:2]
        if line not in latest or version > latest[line]:
            latest[line] = version
    newest = sorted(latest.values(), reverse=True)[:lines]
    return [".".join(map(str, version)) for version in newest]


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
        tags = subprocess.run(["git", "tag", "--list", "v*"], check=True, capture_output=True, text=True).stdout
        versions = supported_versions(tags.splitlines())
        if not versions:
            print("no release tags found; fetch tags first", file=sys.stderr)
            return 1
        print(json.dumps(image_refs(args.repository, versions)))
        return 0
    return triage(args.reports, args.repository, args.dry_run, args.max_new)


if __name__ == "__main__":
    sys.exit(main())
