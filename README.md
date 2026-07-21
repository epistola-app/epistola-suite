# Epistola Suite

[![Build and Publish](https://github.com/epistola-app/epistola-suite/actions/workflows/build.yml/badge.svg)](https://github.com/epistola-app/epistola-suite/actions/workflows/build.yml)
[![Coverage](https://img.shields.io/endpoint?url=https://raw.githubusercontent.com/epistola-app/epistola-suite/main/.github/badges/coverage.json)](https://github.com/epistola-app/epistola-suite/actions/workflows/build.yml)
[![Security Scan](https://img.shields.io/endpoint?url=https://raw.githubusercontent.com/epistola-app/epistola-suite/main/.github/badges/trivy.json)](https://github.com/epistola-app/epistola-suite/actions/workflows/security-scan.yml)
[![License: AGPL-3.0](https://img.shields.io/badge/License-AGPL_3.0-blue.svg)](https://www.gnu.org/licenses/agpl-3.0)

> [!NOTE]
> **1.0.0-RC1 is out — first stable release (25 June 2026); 1.0.0-GA planned for July 2026.** **From this release onward the database will no longer be reset between versions** — your data persists across upgrades, and every schema change ships as a forward migration.

> [!WARNING]
> **Release candidate — some APIs may still change before 1.0.0-GA.**
>
> Your data is now stable across upgrades, but while Epistola is in release-candidate phase the REST APIs, catalog formats, configuration, and internal architecture may still be adjusted before 1.0.0-GA. Review the [changelog](CHANGELOG.md) before updating.

> [!IMPORTANT]
> **Provided "as is", without warranty.** Epistola takes no responsibility for the correctness of output or for any damages arising from use of this software. Correctness assurances, support, and liability coverage are available only under a commercial SLA — see the [Disclaimer](DISCLAIMER.md).

> [!NOTE]
> **Supported versions and upgrades.** Epistola follows continuous delivery with a limited set of supported release lines and low-friction upgrades (not long-lived legacy support). See the [Release & Support Policy](SUPPORT_POLICY.md).

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

1. Install [mise](https://mise.jdx.dev/getting-started.html) version manager

2. Run the init script to set up your development environment:

```bash
./scripts/init.sh
```

This will:

- Configure mise in your shell (bash/zsh) if not already done
- Ensure a UTF-8 locale (see [Platform support](#platform-support) below)
- Install the required tool versions (Java Temurin 25, Node.js 24)
- Install Git hooks (commitlint for conventional commit validation)
- Configure SSH commit signing (if using SSH remote)

### Platform support

Development is actively tested on **macOS** and **Linux**. **Windows is not
tested**; if you're on Windows, develop inside **WSL2** (treated as Linux).

Your shell must use a **UTF-8 locale** (`LANG`/`LC_ALL` ending in `.UTF-8`). The
JVM derives `sun.jnu.encoding` — the charset for file paths — from the OS
locale, and a non-UTF-8 locale (the POSIX `C` locale is the default on many WSL
and minimal Linux images) breaks Kotlin builds when a source name contains a
non-ASCII character:

```
java.nio.file.InvalidPathException: Malformed input or input contains unmappable characters
```

`./scripts/init.sh` configures this automatically. To verify:

```bash
locale charmap                          # -> UTF-8
java -XshowSettings:properties -version 2>&1 | grep sun.jnu.encoding
#                                       # -> sun.jnu.encoding = UTF-8
```

If you change the locale, run `gradle --stop` so the daemon restarts with it.

### Build and Run

```bash
# Build the entire project
gradle build

# Run the application (requires a profile — see Authentication below)
gradle :apps:epistola:bootRun --args='--spring.profiles.active=local'
```

### Authentication

A Spring profile **must** be set to configure authentication. Without one, the app will fail to start.

| Setup             | Auth Method                                  | Use Case                   |
| ----------------- | -------------------------------------------- | -------------------------- |
| `local`           | Form login (in-memory users)                 | Local development          |
| `local,keycloak`  | Form login + OAuth2/OIDC with local Keycloak | Testing both login methods |
| `localauth`       | Form login with env-var credentials          | Staging/test fallback      |
| `prod` + OIDC env | OAuth2/OIDC with any compliant provider      | Production                 |

**Local development** — uses in-memory users, no external dependencies:

```bash
gradle :apps:epistola:bootRun --args='--spring.profiles.active=local'
# Login: admin@local / admin  or  user@local / user
```

**With local services** — start PostgreSQL and Keycloak via the unified Docker Compose:

```bash
# Start PostgreSQL + Keycloak (admin console at http://localhost:4002, admin/admin)
docker compose -f apps/epistola/docker/docker-compose.yaml up -d

# Or start only PostgreSQL (sufficient for local profile)
docker compose -f apps/epistola/docker/docker-compose.yaml up -d postgres

# Run with form login and OAuth2
gradle :apps:epistola:bootRun --args='--spring.profiles.active=local,keycloak'
```

**Production** — configure OIDC via environment variables or the Helm chart's `oidc.*` values. Do not use the `keycloak` profile outside local development.

```bash
export SPRING_PROFILES_ACTIVE=prod
export SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_KEYCLOAK_CLIENTID=epistola-suite
export SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_KEYCLOAK_CLIENTSECRET=<your-secret>
export SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_KEYCLOAK_SCOPE=openid,profile,email
export SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_KEYCLOAK_ISSUERURI=https://sso.example.com/realms/epistola
export SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUERURI=https://sso.example.com/realms/epistola
```

See [docs/auth.md](docs/auth.md) for full details on OIDC setup, auto-provisioning, and safety guards.

### Docker

Build a Docker image:

```bash
gradle :apps:epistola:bootBuildImage
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
gradle ktlintCheck

# Auto-format code
gradle ktlintFormat
```

### Testing

```bash
gradle test
```

Tests use [Testcontainers](https://testcontainers.org/) which requires Docker to be running.

### GitHub MCP (AI-Assisted Development)

This project includes a GitHub MCP server for AI-assisted issue and project management. It enables AI assistants (like Claude Code) to interact with GitHub Issues and Projects.

**Setup:**

```bash
pnpm run setup:github-mcp
```

This will:

1. Open GitHub to create a fine-grained Personal Access Token with minimal permissions
2. Validate the token
3. Store it securely in your OS credential manager (macOS Keychain or Windows Credential Vault)

**Features:**

- Create and manage GitHub Issues
- Work with Pull Requests
- Track backlog in GitHub Projects

The configuration is in `.mcp.json` (safe to commit - no secrets stored).

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

## Support and upgrades

Epistola publishes releases when ready and expects installations to stay on a
**supported** version. Upgrades are designed to be direct (no intermediate
releases required), with automated migrations and documented manual steps when
needed. Downgrades after a successful upgrade are not supported; rollback of a
**failed** upgrade is supported via backup restore.

Full details: [SUPPORT_POLICY.md](SUPPORT_POLICY.md). Currently supported
versions are published separately (website / release metadata), not hardcoded
in the policy. See also [docs/version-check.md](docs/version-check.md) and
[docs/migrations.md](docs/migrations.md).

## License

Epistola Suite is licensed under the [GNU Affero General Public License v3](LICENSE).

The software is provided **"as is", without warranty of any kind**. Epistola
accepts no responsibility for the correctness of output or for any damages
incurred through use of the software. Correctness assurances, defect
remediation, support, and contractual liability coverage are available only
under a separate commercial **Service Level Agreement (SLA)**. See
[DISCLAIMER.md](DISCLAIMER.md) for the full statement.
