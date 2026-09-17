# Vulnerability records

**Read this before touching anything in this folder.** Vulnerability work is confidential until
disclosure, and the ordering below is the point of the process, not ceremony. The repository — not
GitHub — is the source of truth for published vulnerability information.

## Confidentiality

Never discuss an uncoordinated vulnerability in a public issue, branch, pull request, commit or
fork. Do not add its record here on a public branch while supported users still lack a patched
release: pushing the branch would itself be the disclosure.

Coordination and development happen in a draft GitHub Security Advisory and the temporary private
fork created from it. An ordinary fork of this repository is public — do not use one. After
disclosure, the GitHub advisory becomes a mirror of the record here.

## Working an advisory

1. Create or accept a draft advisory, assign the internal ID, and add only the people needed to
   triage, fix, review and verify. Sensitive detail and proof-of-concept material stay in the
   advisory discussion.
2. Develop the regression test, the fix, the record and the release prep in the temporary private
   fork. GitHub runs no status checks there, so run the repository's checks yourself and record the
   results in the advisory. Every open private-fork PR must be mergeable, and only one may target
   its `main`.
3. Establish affected versions from tagged source, score with a documented CVSS vector and its
   assumptions, identify the full 40-character fixed commit, choose the patched version, and agree
   the disclosure timing. Do not call a mitigation a fix unless it closes every supported exploit
   path.
4. At disclosure: merge through the advisory's **Merge pull request(s)** action, release the patched
   version immediately, then publish the dated record here and the GitHub advisory. Keep the gap
   between public fix commits and the release as short as you can. GitHub deletes the private fork
   on publication, so keep anything auditable in the advisory or the public record.

## Record format

One dated Markdown file per vulnerability, `YYYY-MM-DD-<lowercase-id>.md` — the date matches the
frontmatter `published` timestamp and the filename contains the advisory ID. It opens with strict
JSON frontmatter between `---` delimiters (JSON is valid YAML 1.2 and carries the OSV metadata), and
the human-readable description follows in the body under one level-one heading. Every OSV `GIT`
range event uses a full 40-character commit hash. Record affected ranges, severity, CWE IDs,
references, mitigation and the first patched release.

**Third-party findings are a second record kind, not a second system.** A CVE a scanner reports
against a component we ship is recorded with `"kind": "dependency"`: the version-pinned purl, the
findings, and an [OpenVEX](https://openvex.dev) assessment (`not_affected` / `affected` / `fixed` /
`under_investigation`, with a justification for `not_affected`). These are not restated in
`VULNERABILITIES.md`, are excluded from the OSV export, and can never sync to GitHub — the validator
rejects `sync: true` or a stray `affected` block. An OSV document asserts the named package is
vulnerable, and neither "we are affected by someone else's CVE" nor an advisory about someone else's
project is ours to publish. Prefer upgrading over arguing non-exploitability, and record the
reachability evidence either way ([`docs/sbom.md`](../docs/sbom.md#assessing-a-scanner-finding)).

## Publication and sync

GitHub sync is opt-in per record via `database_specific.github.sync`. Use `state: draft` while
preparing privately; before the record reaches a public branch, populate `patched_release`,
`patched_versions` and the assigned GHSA alias, and add any CVE to `aliases`. Set
`state: published` only once the patched release exists — the validator rejects publication without
`patched_versions`.

Syncing is an explicit maintainer action, never automated: run
`scripts/vulnerability_advisories.py sync-github` with `GITHUB_REPOSITORY` and a
`SECURITY_ADVISORY_TOKEN` limited to `Repository security advisories: write`. The CLI never deletes
advisories or requests CVEs. Do not add advisory synchronization to Actions — that workflow
validates and exports only, and must stay independent of GitHub credentials.

## Verify

```bash
pnpm vulnerabilities:check     # validates records; run before committing
pnpm vulnerabilities:render    # regenerates VULNERABILITIES.md — never edit it by hand
pnpm vulnerabilities:export    # portable OSV JSON under build/osv/
pnpm vulnerabilities:export-vex
```
