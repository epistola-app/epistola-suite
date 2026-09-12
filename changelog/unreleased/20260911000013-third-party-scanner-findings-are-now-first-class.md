---
type: feat
scopes: [security]
audience: dev
title: Third-party scanner findings are now first-class records with an OpenVEX assessment.
---

A CVE reported against a component we ship had nowhere to live but a build-file comment. Records under `vulnerabilities/` now carry a `kind`: the existing OSV/GHSA-shaped ones are `advisory`, while a scanner finding is `dependency` — the component purls, the CVE identifiers, and an [OpenVEX](https://openvex.dev) assessment. Dependency records are excluded from the OSV export and can never reach GitHub Security Advisories: an advisory about someone else's project is not ours to publish. `pnpm vulnerabilities:export-vex` emits the VEX document, CI applies it to the Trivy scan, and it ships beside the SBOMs so operators can apply our assessments to what they scan. A record is written only when we are asserting something; a finding closed by a routine upgrade gets none. See [`docs/sbom.md`](docs/sbom.md#assessing-a-scanner-finding).
