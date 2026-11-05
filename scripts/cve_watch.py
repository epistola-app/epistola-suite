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

``check-support``
    Compares the support floor that installations read (``support`` in the
    published ``releases.json``) with SUPPORT_POLICY.md and with the maintenance
    branches, and fails when they disagree. The website computes that block from
    the same GitHub release dates by the same rules (src/lib/server/releases/support.ts
    in epistola-app/website), so a disagreement means one of them changed: a line still advertised after its
    end, a line dropped before it, an end date not announced, a supported major
    with no branch to patch it from, or a branch for a line nobody is promised.

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
import urllib.request
from dataclasses import dataclass, field
from datetime import date, datetime, timedelta, timezone
from pathlib import Path
from typing import Any

LABEL = "cve-watch"
TITLE_PREFIX = "[cve-watch]"
VERSION_TAG = re.compile(r"^v(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z]+(?:\.[0-9A-Za-z]+)*))?$")
TITLE_ID = re.compile(r"^\[cve-watch\] (\S+)")
SEVERITY_ORDER = ["UNKNOWN", "LOW", "MEDIUM", "HIGH", "CRITICAL"]
IMAGE_VARIANTS = ["", "-demo"]
RELEASES_URL = "https://epistola.app/.well-known/epistola/releases.json"
PRODUCT = "epistola-suite"
# SUPPORT_POLICY.md: how long a line stays supported once its successor ships.
MINOR_GRACE = timedelta(days=30)
MAJOR_OVERLAP_MONTHS = 3
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
    major = branch_major(tags)
    if major is None:
        return []
    versions = [version for tag in tags if (version := Version.parse(tag))]
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


Line = tuple[int, int]


def add_months(day: date, months: int) -> date:
    month = day.month - 1 + months
    year, month = day.year + month // 12, month % 12 + 1
    for last in (31, 30, 29, 28):
        try:
            return date(year, month, min(day.day, last))
        except ValueError:
            continue
    raise AssertionError("unreachable")


def line_starts(release_dates: dict[str, date]) -> dict[Line, date]:
    """When each minor line shipped: the date of its first release (pre-releases do not count)."""
    starts: dict[Line, date] = {}
    for tag, day in release_dates.items():
        version = Version.parse(tag)
        if version and not version.pre_release:
            line = (version.major, version.minor)
            starts[line] = min(day, starts.get(line, day))
    return starts


def policy_ends(starts: dict[Line, date]) -> dict[Line, date | None]:
    """The last supported day per SUPPORT_POLICY.md; ``None`` while the line has no successor.

    A minor line ends 30 days after the next minor of its major ships, or as soon as
    a second one does: a major has at most two supported lines, the newest and the
    one it replaced. The newest minor of a major ends three months after the next
    major's first release.
    """
    ends: dict[Line, date | None] = {}
    for line in starts:
        later_minors = sorted(start for (major, minor), start in starts.items() if major == line[0] and minor > line[1])
        later_majors = [start for (major, _), start in starts.items() if major > line[0]]
        if later_minors:
            grace_end = later_minors[0] + MINOR_GRACE
            ends[line] = min(grace_end, later_minors[1]) if len(later_minors) > 1 else grace_end
        elif later_majors:
            ends[line] = add_months(min(later_majors), MAJOR_OVERLAP_MONTHS)
        else:
            ends[line] = None
    return ends


def line_name(line: Line) -> str:
    return f"{line[0]}.{line[1]}"


def support_problems(
    support: dict[str, Any] | None,
    release_dates: dict[str, date],
    tags_per_branch: dict[str, list[str]],
    today: date,
) -> list[str]:
    """Where the published support block, the policy and the maintenance branches disagree."""
    starts = line_starts(release_dates)
    if not starts:
        return []
    ends = policy_ends(starts)
    supported = sorted(line for line, end in ends.items() if end is None or end >= today)
    expected = supported[0]
    expected_until = ends[expected]

    floor = Version.parse(f"v{(support or {}).get('minVersion', '')}")
    if floor is None:
        return [f"releases.json publishes no support.minVersion; the policy's floor is {line_name(expected)}.0."]
    published = (floor.major, floor.minor)
    until_text = (support or {}).get("until")
    until = date.fromisoformat(until_text) if until_text else None

    problems: list[str] = []
    if published > expected:
        problems.append(
            f"releases.json drops {line_name(expected)} (support.minVersion {floor}), but the policy supports it "
            f"until {expected_until}. An end date is never brought forward."
        )
    elif published < expected and not (until and until >= today):
        ended = ends[published]
        problems.append(
            f"releases.json still supports {line_name(published)} (support.minVersion {floor}), whose support "
            f"ended on {ended}. Raise support.minVersion to {line_name(expected)}.0"
            + (f" with support.until {expected_until}." if expected_until else ".")
            + " To extend support on purpose, set support.until to the new end date."
        )
    elif published == expected and expected_until and not (until and until >= expected_until):
        problems.append(
            f"The policy supports {line_name(expected)} until {expected_until}, but releases.json "
            + (f"publishes support.until {until}." if until else "announces no support.until.")
            + f" Set support.until to {expected_until} (or later, to extend it)."
        )

    # A line can only be patched from a branch: main, or release/<major>.x.
    majors_by_branch = {branch: branch_major(tags) for branch, tags in tags_per_branch.items()}
    advertised = {major for major, minor in starts if (major, minor) >= published}
    for major in sorted(advertised - set(majors_by_branch.values())):
        problems.append(f"releases.json supports {major}.x, but no branch maintains it: cut release/{major}.x.")
    for branch, major in sorted(majors_by_branch.items()):
        if branch != "main" and major is not None and major not in advertised:
            problems.append(f"{branch} maintains {major}.x, which releases.json no longer supports: delete the branch.")
    return problems


def branch_major(tags: list[str]) -> int | None:
    versions = [version for tag in tags if (version := Version.parse(tag))]
    return max(versions, key=Version.precedence).major if versions else None


def published_support(url: str) -> dict[str, Any] | None:
    request = urllib.request.Request(url, headers={"User-Agent": "epistola-cve-watch"})
    with urllib.request.urlopen(request, timeout=30) as response:
        document = json.load(response)
    return document.get("products", {}).get(PRODUCT, {}).get("support")


def github_release_dates(repository: str) -> dict[str, date]:
    """Each published release's tag and UTC publication date: what the website dates lines by too."""
    output = gh(
        "api", f"repos/{repository}/releases?per_page=100", "--paginate",
        "--jq", '.[] | select(.draft | not) | select(.published_at) | "\\(.tag_name) \\(.published_at[:10])"',
    )
    return {tag: date.fromisoformat(day) for tag, day in (line.split() for line in output.splitlines())}


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

    support_parser = commands.add_parser("check-support", help="compare releases.json with the policy and branches")
    support_parser.add_argument("--releases-url", default=RELEASES_URL)
    support_parser.add_argument("--repository", required=True, help="owner/name, e.g. epistola-app/epistola-suite")

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
    if args.command == "check-support":
        support = published_support(args.releases_url)
        today = datetime.now(timezone.utc).date()
        problems = support_problems(support, github_release_dates(args.repository), maintained_branch_tags(), today)
        print(f"Published support: {json.dumps(support)}")
        for problem in problems:
            print(f"- {problem}")
        if not problems:
            print("The published support floor matches SUPPORT_POLICY.md and the maintenance branches.")
        return 1 if problems else 0
    return triage(args.reports, args.repository, args.dry_run, args.max_new)


if __name__ == "__main__":
    sys.exit(main())
