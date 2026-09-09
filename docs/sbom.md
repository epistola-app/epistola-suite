# Software Bill of Materials (SBOM)

Epistola Suite generates SBOMs for both backend and frontend dependencies using the [CycloneDX](https://cyclonedx.org/) standard.

## What is an SBOM?

A Software Bill of Materials is a formal, machine-readable inventory of software components and dependencies. It enables:

- **Security scanning**: Identify known vulnerabilities (CVEs) in dependencies
- **License compliance**: Track licenses of all components
- **Supply chain transparency**: Know exactly what's in your software
- **Incident response**: Quickly determine if you're affected by a newly disclosed vulnerability

## SBOM Generation

### Backend (Kotlin/Java)

Uses the [CycloneDX Gradle Plugin](https://github.com/CycloneDX/cyclonedx-gradle-plugin).

```bash
gradle :apps:epistola:generateSbom
```

**Output:** `apps/epistola/build/sbom/bom.json`

### Frontend (TypeScript/npm)

Uses [cdxgen](https://github.com/CycloneDX/cdxgen).

```bash
pnpm --filter @epistola/editor sbom
```

**Output:** `modules/editor/build/sbom.json`

### Generate Both

```bash
gradle :apps:epistola:generateSbom
pnpm --filter @epistola/editor sbom
```

## SBOM Locations

| Location       | File                                   | Contents              |
| -------------- | -------------------------------------- | --------------------- |
| GitHub Release | `epistola-backend-{version}-sbom.json` | Backend dependencies  |
| GitHub Release | `epistola-editor-{version}-sbom.json`  | Frontend dependencies |
| Docker Image   | `META-INF/sbom/bom.json`               | Backend dependencies  |
| CI Artifact    | `sbom` (workflow artifact)             | Both SBOMs            |

## Format Details

| Property       | Backend   | Frontend  |
| -------------- | --------- | --------- |
| Standard       | CycloneDX | CycloneDX |
| Format         | JSON      | JSON      |
| Schema Version | 1.6       | 1.6       |
| Components     | ~290      | ~55       |

## Using the SBOM

### View Component Count

```bash
cat apps/epistola/build/sbom/bom.json | jq '.components | length'
```

### List All Components

```bash
cat apps/epistola/build/sbom/bom.json | jq '.components[].name'
```

### Find a Specific Dependency

```bash
cat apps/epistola/build/sbom/bom.json | jq '.components[] | select(.name | contains("spring"))'
```

### Extract from Docker Image

```bash
docker run --rm epistola:latest \
  cat /workspace/BOOT-INF/classes/META-INF/sbom/bom.json > sbom.json
```

## Vulnerability Scanning

### Automatic Scanning (CI/CD)

Trivy automatically scans both SBOMs on every push and pull request:

- **Scans**: Backend and frontend SBOMs
- **Fails on**: Critical vulnerabilities
- **Reports**: Uploaded as workflow artifacts (`vulnerability-reports`)

The gate fires on Trivy's CRITICAL label alone, which often disagrees with the upstream
project's own rating, so a red build says nothing about real risk until someone assesses
it. See [Assessing a scanner finding](#assessing-a-scanner-finding) below.

The scans also consume our own VEX document, so a finding already assessed as
`not_affected` does not fail the gate a second time — see
[VEX: recorded assessments](#vex-recorded-assessments).

### Local Scanning

#### Trivy

```bash
# Install Trivy
brew install trivy

# Scan backend
trivy sbom apps/epistola/build/sbom/bom.json

# Scan with severity filter
trivy sbom --severity CRITICAL,HIGH apps/epistola/build/sbom/bom.json
```

#### Grype

```bash
# Install Grype
brew install grype

# Scan backend
grype sbom:apps/epistola/build/sbom/bom.json
```

### OWASP Dependency-Track

Upload the SBOM to [Dependency-Track](https://dependencytrack.org/) for continuous monitoring.

## CI/CD Integration

SBOMs are automatically generated and scanned during CI/CD:

1. **On every build**: Both SBOMs generated and scanned for vulnerabilities
2. **Vulnerability gate**: Build fails if critical vulnerabilities are found
3. **On release**: Both SBOMs attached to the GitHub Release
4. **Docker image**: Backend SBOM embedded in the container
5. **Artifacts**: SBOMs and vulnerability reports uploaded for 7 days

## Assessing a scanner finding

A red gate is the start of the work, not the end of it. Trivy's CRITICAL label is often the
scanner's, not the upstream project's — the three Tomcat CVEs that broke the build in
September 2026 are rated Important, Low and Low by Apache — so the label alone tells you
nothing about whether the suite is exposed.

1. **Read the upstream advisory, not the scanner summary.** Apache, Spring and Thymeleaf all
   publish the preconditions for exploitation. The scanner's one-line title does not.
2. **Establish reachability against this codebase, and record the evidence.** The grep that
   came back empty _is_ the finding. Do not reason from "we probably don't use that" — an
   assessment nobody can check is worth nothing to the operator reading it later.
3. **Prefer upgrading over arguing.** If a fixed release exists and is a drop-in, take it even
   when the finding is unreachable. Non-exploitability is an argument you have to keep making;
   a version bump ends it.
4. **Write a record only if you are asserting something.** See below.

### When a record is needed

Most findings do not need one. If an upgrade fixes it and the gate goes green, **you are
done** — the dependency bump and its CHANGELOG entry already say what happened, and a record
restating that is pure overhead.

Write a `kind: dependency` record under [`vulnerabilities/`](../vulnerabilities/) only when
you are making a claim someone might need to check:

- **`not_affected`** — you are suppressing a finding via VEX, and the record is what justifies
  the suppression.
- **`affected`** — you are knowingly shipping an unfixed finding, and someone has to own that.

Either way, also add a CHANGELOG entry and a comment on any version override saying when it
can be dropped. That comment matters: the pin preceding the September 2026 one had silently
become a _downgrade_ (it held Tomcat 11.0.22 while the Spring Boot BOM had moved to 11.0.24),
and nothing in the tree recorded enough to notice.

### Record shape

Dependency records live alongside Epistola's own advisories in
[`vulnerabilities/`](../vulnerabilities/), discriminated by `"kind": "dependency"`. They are
deliberately not restated in [`VULNERABILITIES.md`](../VULNERABILITIES.md): the folder is
browsable and `epistola.openvex.json` is the machine-readable list, so a markdown table would
be a worse copy needing a re-render and a commit on every change. Each record carries an
[OpenVEX](https://openvex.dev) assessment:

| Field                      | Meaning                                                                                  |
| -------------------------- | ---------------------------------------------------------------------------------------- |
| `component.purls[]`        | Every affected version, each **version-pinned** (see below)                              |
| `vulnerabilities[]`        | The CVE identifiers this record covers                                                   |
| `assessment.status`        | `not_affected`, `affected`, `fixed`, or `under_investigation`                            |
| `assessment.justification` | For `not_affected`, one of the five OpenVEX justifications                               |
| `remediation`              | What we did — typically `upgraded`, with the fixed version and the release it shipped in |

CVE titles, upstream severities and exploitation preconditions go in the Markdown body, not
the frontmatter: they are the argument a reader needs, and structured copies of them would
only duplicate the body and rot.

**List every affected version you shipped, not just the one the scanner reported.** These are
rarely the same. The September 2026 Tomcat finding was flagged against 11.0.24, but the pin
held 11.0.22 through v1.1.0, so 11.0.24 was never released — a VEX naming only the scanned
version would have matched nothing anyone runs.

Dependency records are **excluded from the OSV export and can never sync to GitHub Security
Advisories**, and the validator rejects both a `sync: true` and a stray `affected` block. An
OSV document asserts the named package is vulnerable; neither "Epistola is vulnerable to
someone else's CVE" nor an advisory about Apache Tomcat is a claim this repository publishes.

## VEX: recorded assessments

`pnpm vulnerabilities:export-vex` turns the dependency records into a single
[OpenVEX](https://openvex.dev) document at `build/vex/epistola.openvex.json`, with one
statement per CVE.

```bash
pnpm vulnerabilities:export-vex
```

CI generates it before the Trivy steps and passes it via the `TRIVY_VEX` environment
variable, so a finding recorded as `not_affected` no longer fails the gate — and the reason
is in git rather than in a `.trivyignore` with no explanation. Note that `trivy-action` has
no `vex` input and a `vex:` key in `trivy.yaml` is silently ignored; the environment variable
is the supported route.

The document is attached to each release as `epistola.openvex.json`, next to the SBOMs. To
apply our assessments to an artifact you scan yourself:

```bash
trivy sbom epistola-backend-sbom.json --vex epistola.openvex.json
```

**VEX products are version-pinned on purpose.** A statement names
`pkg:maven/org.apache.tomcat.embed/tomcat-embed-core@11.0.24`, not the versionless purl. A
versionless statement would match every future version too, silently outliving the analysis
it rests on and potentially suppressing a genuinely different vulnerable path introduced
later. Pinning means an upgrade re-surfaces the finding for a fresh assessment.

Two caveats. Trivy marks `--vex` **experimental** as of 0.74. And a VEX statement suppresses
a scanner finding — it does not make the code safe; the reachability analysis in the record
body is what does or does not justify it.

## Third-Party License Notices

The SBOMs above are machine-readable inventories. Separately, Epistola Suite generates a
human-readable **third-party notices** document that reproduces the copyright and full
license texts of every bundled dependency. This satisfies the attribution requirements of
permissive licenses (MIT/BSD/ISC copyright notices, Apache-2.0 §4, the SIL Open Font
License for the bundled fonts). It is distinct from Epistola's own AGPL-3.0 license (see
the repository `LICENSE`), which does not discharge third-party attribution.

Unlike the SBOMs (SPDX identifiers only), the notices file inlines the actual license
texts, gathered by dedicated tooling per ecosystem:

- **Backend (JVM/Maven):** the [jk1 dependency-license-report](https://github.com/jk1/Gradle-License-Report)
  Gradle plugin over the `runtimeClasspath` (first-party `app.epistola.*` artifacts excluded).
- **Frontend (npm):** [generate-license-file](https://github.com/TomChristian/generate-license-file)
  over the editor module's production dependencies.
- **Fonts:** the bundled OFL 1.1 notices (`LICENSE-LiberationFonts` and each system font's `OFL.txt`).

### Generate

```bash
# Frontend report first (the Gradle merge task reads it)
pnpm --filter @epistola/editor notices
# Merge backend + frontend + fonts into the consolidated file
gradle :apps:epistola:generateThirdPartyNotices
```

**Output:** `apps/epistola/build/notices/THIRD-PARTY-NOTICES.md`

### Locations

| Location       | File                                       | Contents                   |
| -------------- | ------------------------------------------ | -------------------------- |
| GitHub Release | `THIRD-PARTY-NOTICES.md`                   | Backend + frontend + fonts |
| Docker Image   | `META-INF/licenses/THIRD-PARTY-NOTICES.md` | Backend + frontend + fonts |

## Further Reading

- [CycloneDX Specification](https://cyclonedx.org/specification/overview/)
- [NTIA SBOM Minimum Elements](https://www.ntia.gov/page/software-bill-materials)
- [CISA SBOM Resources](https://www.cisa.gov/sbom)
