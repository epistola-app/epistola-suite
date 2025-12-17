# Epistola Suite

A document suite application built with Spring Boot 4.0.0 and Kotlin.

## Getting Started

### Prerequisites

1. Install [asdf](https://asdf-vm.com/guide/getting-started.html) version manager

2. Run the init script to set up your development environment:

```bash
./scripts/init.sh
```

This will:
- Configure asdf in your shell (bash/zsh) if not already done
- Install the required tool versions (Java Temurin 25)

### Build and Run

```bash
# Build the project
./gradlew build

# Run the application
./gradlew bootRun
```

### Docker

Build a Docker image:

```bash
./gradlew bootBuildImage
```

Run the container:

```bash
docker run --rm -p 8080:8080 epistola-suite:0.0.1-SNAPSHOT
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

### Conventional Commits

Version bumps are determined automatically from commit messages:
- `feat:` - minor version bump
- `fix:` - patch version bump
- `feat!:` or `fix!:` with `BREAKING CHANGE` - major version bump

## License

See [LICENSE](LICENSE) for details.
