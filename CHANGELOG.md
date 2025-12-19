# Epistola Suite Changelog

## [Unreleased]

### Changed
- Migrated version management from asdf to mise for faster tool installation

- Updated documentation to reflect server-side rendering architecture (Thymeleaf + HTMX) instead of implying a Vite/TypeScript SPA frontend
  - CLAUDE.md: Added Frontend Architecture section, clarified project overview and structure
  - README.md: Added Architecture section, updated project description and modules
  - CONTRIBUTING.md: Added Thymeleaf + HTMX code style section, updated frontend label description
  - .github/labels.yml: Updated frontend label description

### Fixed
- Labels sync workflow now works with private repositories by adding `contents: read` permission
- Docker image build in CI now explicitly sets image name to `epistola-suite` via `--imageName` flag to ensure consistent naming across build and push steps
- Helm chart security scan failures (AVD-KSV-0014, AVD-KSV-0118): added proper pod and container security contexts with `readOnlyRootFilesystem`, `seccompProfile`, `allowPrivilegeEscalation: false`, and capability drops

### Added
- Test coverage reporting using Kover with dynamic badge
  - Generates coverage reports via `./gradlew koverXmlReport`
  - Coverage badge updated automatically on main branch builds
  - Badge displayed in README alongside build and security badges
- Scheduled security scan workflow (daily) with dynamic vulnerability badge
  - Runs Trivy scans on SBOMs daily at 6 AM UTC
  - Updates badge in `.github/badges/trivy.json` showing vulnerability count
  - Automatically creates GitHub issue when critical vulnerabilities are detected
- README badges for CI build status, security scan, and AGPL-3.0 license
- Manual trigger for Helm chart workflow via `workflow_dispatch` with optional `force_release` input
- Docker image signing with Cosign (keyless OIDC)
  - All published images are cryptographically signed using Sigstore
  - SBOM attestation attached to images using CycloneDX format
  - Verify signatures: `cosign verify ghcr.io/epistola-app/epistola-suite:<tag> --certificate-identity-regexp='.*' --certificate-oidc-issuer='https://token.actions.githubusercontent.com'`
  - Verify SBOM attestation: `cosign verify-attestation ghcr.io/epistola-app/epistola-suite:<tag> --type cyclonedx --certificate-identity-regexp='.*' --certificate-oidc-issuer='https://token.actions.githubusercontent.com'`
- Helm chart for Kubernetes deployment
  - Published to OCI registry at `oci://ghcr.io/epistola-app/charts/epistola`
  - Separate versioning from the application using `chart-X.Y.Z` tags
  - Automatic version bumping based on conventional commits for changes in `charts/` directory
  - Includes: Deployment, Service, Ingress (optional), HPA (optional), ServiceAccount, ConfigMap
  - Trivy security scanning for Kubernetes misconfigurations
  - Spring Boot Actuator health probes (liveness/readiness) configured
- SBOM (Software Bill of Materials) generation using CycloneDX
  - Backend SBOM: `./gradlew :apps:epistola:generateSbom`
  - Frontend SBOM: `./gradlew :modules:editor:npmSbom`
  - Both SBOMs attached to GitHub Releases (`epistola-backend-{version}-sbom.json`, `epistola-editor-{version}-sbom.json`)
  - Backend SBOM embedded in Docker images at `META-INF/sbom/bom.json`
- Automatic vulnerability scanning using Trivy
  - Scans both backend and frontend SBOMs on every build
  - Fails build on critical vulnerabilities
  - Vulnerability reports uploaded as CI artifacts
- GitHub Releases are now created automatically with release notes and SBOM attachment
- Open source community infrastructure
  - CONTRIBUTING.md with development workflow, commit conventions, and code style guidelines
  - CODE_OF_CONDUCT.md based on Contributor Covenant v2.1
  - SECURITY.md with vulnerability reporting guidelines and 48-hour response SLA
  - GitHub issue templates (bug report, feature request, documentation)
  - Pull request template with checklist
  - Issue template config linking to GitHub Discussions for questions
  - Automated label management via GitHub Actions (`.github/labels.yml`)
  - Comprehensive GitHub documentation (`docs/github.md`) covering CI/CD, releases, labels, and workflows
  - CLAUDE.md with project-specific instructions for Claude Code AI assistant
  - Git hooks with commitlint for conventional commit validation
  - SSH commit signing auto-configuration in init script
- Initial project setup with Spring Boot 4.0.0 and Kotlin 2.3.0
- Multi-module Gradle structure with apps/epistola and modules/editor
- Vite-based editor module with TypeScript, embeddable in the Java application
- GitHub Actions CI/CD pipeline with build, test, and Docker image publishing to ghcr.io
- Automatic semantic versioning using github-tag-action based on conventional commits
- ktlint for consistent code style
- README with getting started guide using mise for version management
