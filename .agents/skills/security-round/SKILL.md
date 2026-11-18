---
name: security-round
description: Run a round of security fixes over the supported release lines — triage CVE-watch issues, code-scanning alerts and open security issues, fix what affects a supported line, and draft a message to customers and partners. Use when asked for a security round, CVE sweep or security update.
---

A round answers one question: is anything we ship and still support exposed, and if so, what do
customers and partners need to do? Only supported lines are fixed (`SUPPORT_POLICY.md`). An
unsupported release gets no fix, and the answer for it is to upgrade.

## 1. Establish the supported lines

```bash
git fetch --tags origin
python3 scripts/cve_watch.py supported-images --repository epistola-app/epistola-suite
python3 scripts/cve_watch.py check-support --repository epistola-app/epistola-suite
```

`supported-images` prints one line per maintained branch (`main`, `release/<major>.x`) and the image
refs the daily watch scans. Under the policy:

- the newest minor of the current major gets every fix;
- the last minor of the previous major gets security fixes as patch releases for three months after
  the next major's X.0.0;
- the previous minor gets none, because upgrading to the newest minor is the fix;
- pre-releases are not production and pick up fixes in the next pre-release.

A failing `check-support` is itself a finding: `releases.json` and the policy disagree.

## 2. Read the current scan, not the issue backlog

Download the latest `Security Scan` run. Its `trivy-image-*` artifacts are today's image scans,
with our VEX applied: `trivy-image.json` for the OS packages and jars, `runtime-image.json` for
the Java runtime, which Trivy cannot see:

```bash
gh run list --workflow security-scan.yml --limit 1
gh run download <run-id> --dir <scratch>/scan
```

Confirm each report is real before you trust a zero: `ArtifactName` is a supported image and it
lists packages (`[.Results[]?.Packages[]?] | length` is in the hundreds). A missing or empty report
means a failed scan, not a clean one. If the run is more than a day old, scan locally instead:
`trivy image --format json` with `TRIVY_VEX` set to the output of `pnpm vulnerabilities:export-vex`, plus
`python3 scripts/cve_watch.py runtime-scan <image> --output runtime.json`.

## 3. Triage everything open

- **`cve-watch` issues** (`gh issue list --label cve-watch`). The watch closes an issue once no
  supported image reports its CVE, so an issue that is still open names a finding in a supported
  image. Triage it with
  [Assessing a scanner finding](../../../docs/sbom.md#assessing-a-scanner-finding): upgrade if you
  can, otherwise add a `kind: dependency` record under `vulnerabilities/`.
- **Code scanning**:
  `gh api '/repos/epistola-app/epistola-suite/code-scanning/alerts?state=open' --paginate`.
  Read the flagged code. Fix a real alert. Dismiss a false positive with a reason and a comment that
  gives the evidence, for example the sanitiser already applied on that line.
- **Dependabot and secret scanning**: the same API, under `dependabot/alerts` and
  `secret-scanning/alerts`.
- **Open security issues**: `gh issue list --label "domain: security"`. Confidential work is on
  Forgejo (`tea issues --repo epistola-product/epistola-suite`). Never mention it in a public issue,
  PR or commit.

A vulnerability in our own code follows [`vulnerabilities/AGENTS.md`](../../../vulnerabilities/AGENTS.md):
a draft advisory and its private fork, never a public branch, until the patched release exists.

## 4. Fix per line

- **A dependency or base-image CVE**: bump on `main`, plus on `release/<major>.x` while the previous
  major still gets security fixes. Each fix needs evidence: the scan before and after.
- **An OS package in a released image**: the fix is a rebuilt image, which means a patch release
  (the `release` skill). Check the newest release first, since it may already carry the patched
  package.
- One PR per line, and a changelog fragment for each user-visible fix.

## 5. Draft the customer and partner message

Draft it only when something affected a supported line or was fixed in this round. Never send or
post it yourself. Hand it to the user, who chooses the channel: email, partner channels or release
notes. Name no customer (public repo rule). Publish no detail of an own-code vulnerability before
its record is published.

Write it for an operator who has five minutes. For each finding, give:

1. **What**: CVE or advisory ID, severity, and the component, in plain words.
2. **Who is affected**: the supported versions that ship it, and whether it is exploitable in
   Epistola (cite the VEX assessment or the reachability evidence).
3. **Fixed in**: the release per line, linked to its GitHub release.
4. **Action**: upgrade to that version, or "none" when we are not affected.

End with the lines checked and the scan date, and one line for anyone on an unsupported version:
upgrade, and here is the supported floor. When nothing was found, tell the user and offer a
one-paragraph all-clear note instead.

## Verify

- Every image from `supported-images` has a real report from today or yesterday.
- No open `cve-watch` issue, code-scanning security alert or Dependabot alert is untriaged. Each is
  fixed, dismissed with evidence, or recorded under `vulnerabilities/`.
- `pnpm vulnerabilities:check` passes if records changed.
- The message draft names the fixed version for every finding it lists, and no customer.
