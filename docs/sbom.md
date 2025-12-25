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

Uses [@cyclonedx/cyclonedx-npm](https://github.com/CycloneDX/cyclonedx-node-npm).

```bash
gradle :modules:editor:npmSbom
```

**Output:** `modules/editor/build/sbom.json`

### Generate Both

```bash
gradle :apps:epistola:generateSbom :modules:editor:npmSbom
```

## SBOM Locations

| Location | File | Contents |
|----------|------|----------|
| GitHub Release | `epistola-backend-{version}-sbom.json` | Backend dependencies |
| GitHub Release | `epistola-editor-{version}-sbom.json` | Frontend dependencies |
| Docker Image | `META-INF/sbom/bom.json` | Backend dependencies |
| CI Artifact | `sbom` (workflow artifact) | Both SBOMs |

## Format Details

| Property | Backend | Frontend |
|----------|---------|----------|
| Standard | CycloneDX | CycloneDX |
| Format | JSON | JSON |
| Schema Version | 1.6 | 1.4 |
| Components | ~290 | ~55 |

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

## Further Reading

- [CycloneDX Specification](https://cyclonedx.org/specification/overview/)
- [NTIA SBOM Minimum Elements](https://www.ntia.gov/page/software-bill-materials)
- [CISA SBOM Resources](https://www.cisa.gov/sbom)
