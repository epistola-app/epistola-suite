# Epistola Suite

[![Build and Publish](https://github.com/epistola-app/epistola-suite/actions/workflows/build.yml/badge.svg)](https://github.com/epistola-app/epistola-suite/actions/workflows/build.yml)
[![Security Scan](https://img.shields.io/endpoint?url=https://raw.githubusercontent.com/epistola-app/epistola-suite/main/.github/badges/trivy.json)](https://github.com/epistola-app/epistola-suite/actions/workflows/security-scan.yml)
[![License: AGPL-3.0](https://img.shields.io/badge/License-AGPL_3.0-blue.svg)](https://www.gnu.org/licenses/agpl-3.0)

A document suite application built with Spring Boot 4.0.0 and Kotlin, featuring server-side rendered views with Thymeleaf and HTMX.

## Project Structure

```
epistola-suite/
├── apps/
│   └── epistola/          # Main Spring Boot application (Thymeleaf + HTMX)
├── modules/
│   └── editor/            # Rich text editor component (Vite + TypeScript)
├── build.gradle.kts       # Root build configuration
└── settings.gradle.kts    # Module includes
```

## Architecture

The frontend uses a **server-side rendering** approach:
- **Thymeleaf**: Template engine for rendering HTML on the server
- **HTMX**: For dynamic interactions without full page reloads
- **Client components**: Embedded JavaScript modules (like the editor) for features requiring rich client-side interactivity

## Getting Started

### Prerequisites

1. Install [asdf](https://asdf-vm.com/guide/getting-started.html) version manager

2. Run the init script to set up your development environment:

```bash
./scripts/init.sh
```

This will:
- Configure asdf in your shell (bash/zsh) if not already done
- Install the required tool versions (Java Temurin 25, Node.js 24)
- Install Git hooks (commitlint for conventional commit validation)
- Configure SSH commit signing (if using SSH remote)

### Build and Run

```bash
# Build the entire project
./gradlew build

# Run the application
./gradlew :apps:epistola:bootRun
```

### Docker

Build a Docker image:

```bash
./gradlew :apps:epistola:bootBuildImage
```

Run the container:

```bash
docker run --rm -p 8080:8080 epistola:0.0.1-SNAPSHOT
```

### Helm Chart

The application can be deployed to Kubernetes using the Helm chart published to the OCI registry.

Install from the OCI registry:

```bash
helm install epistola oci://ghcr.io/epistola-app/charts/epistola --version <version>
```

Upgrade an existing release:

```bash
helm upgrade epistola oci://ghcr.io/epistola-app/charts/epistola --version <version>
```

Customize the deployment with values:

```bash
helm install epistola oci://ghcr.io/epistola-app/charts/epistola \
  --version <version> \
  --set image.tag=1.0.0 \
  --set ingress.enabled=true \
  --set ingress.hosts[0].host=epistola.example.com
```

See `charts/epistola/values.yaml` for all available configuration options.

## Modules

### apps/epistola

The main Spring Boot application that serves the document suite. Uses Thymeleaf for server-side HTML rendering and HTMX for dynamic interactions.

### modules/editor

A Vite-based TypeScript editor component that builds as a library and is embedded in the main application for rich text editing functionality. The built assets are served from `/editor/`.

To develop the editor standalone:

```bash
cd modules/editor
npm install
npm run dev
```

## Development

### Code Style

This project uses [ktlint](https://pinterest.github.io/ktlint/) for Kotlin code style. Run the linter:

```bash
# Check code style
./gradlew ktlintCheck

# Auto-format code
./gradlew ktlintFormat
```

### Testing

```bash
./gradlew test
```

Tests use [Testcontainers](https://testcontainers.org/) which requires Docker to be running.

## CI/CD

The project uses GitHub Actions for CI/CD:

- **Build and test** runs on all PRs and main branch pushes
- **Docker images** are published to ghcr.io:
  - On main: tagged with semantic version (based on conventional commits), SHA, and `latest`
  - On PRs with `publish` label: tagged with `{version}-pr-{number}-{run}`
- **Helm charts** are published to ghcr.io OCI registry:
  - Triggered when changes are detected in the `charts/` directory
  - Tagged with `chart-X.Y.Z` (separate versioning from the app)
  - Published to `oci://ghcr.io/epistola-app/charts`

### Conventional Commits

Version bumps are determined automatically from commit messages:
- `feat:` - minor version bump
- `fix:` - patch version bump
- `feat!:` or `fix!:` with `BREAKING CHANGE` - major version bump

Both the app and Helm chart follow this convention. The Helm chart version is only bumped when changes are made to the `charts/` directory.

## License

See [LICENSE](LICENSE) for details.
