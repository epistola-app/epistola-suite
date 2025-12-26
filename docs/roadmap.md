# Epistola Suite Roadmap

This document provides a high-level overview of planned features and development phases.

## Vision

Epistola Suite is a document generation platform designed for both developers and business users. It enables creating, managing, and generating professional documents (PDF and HTML) from templates with dynamic data binding.

## Target Audience

- **Developers**: REST API and SDK integration for programmatic document generation
- **Business Users**: Visual template editor with no-code template design

## Deployment Models

- **SaaS Multi-tenant**: Hosted service with tenant isolation
- **Self-hosted**: On-premise deployment for organizations requiring data sovereignty

---

## Development Phases

### Phase 1: MVP

**Goal**: Minimal viable product that developers can integrate with.

| Area | Features |
|------|----------|
| **API** | Versioned REST API (`/api/v1/`), OpenAPI 3.1 spec, RFC 7807 errors |
| **Document Generation** | HTML output, PDF via Puppeteer (Chrome-based) |
| **Editor** | Image blocks, undo/redo, table row repetition |
| **Security** | API key authentication and management |
| **Assets** | Image upload with pluggable storage |

### Phase 2: Adoption

**Goal**: Features for broader developer and business user adoption.

| Area | Features |
|------|----------|
| **SDKs** | Java, Python, Node.js client libraries |
| **Security** | Keycloak/OIDC integration, RBAC |
| **Editor** | Headers/footers, page numbers, page breaks |
| **Storage** | S3-compatible and PostgreSQL document storage |
| **Operations** | Template import/export, Prometheus metrics |
| **Documentation** | Landing page, product documentation |

### Phase 3: Enterprise

**Goal**: Features required for enterprise deployment and compliance.

| Area | Features |
|------|----------|
| **Security** | PBAC, GZAC plugin |
| **Document Generation** | PDF accessibility (PDF/UA), digital signatures, metadata |
| **Operations** | Usage tracking, API metrics, load testing |
| **Integrations** | Open Notificaties events |
| **Theming** | Theme definitions, consistent styling |
| **Editor** | Reusable component library, background images |

### Phase 4: Governance (Later)

**Goal**: Template lifecycle management for regulated industries.

| Area | Features |
|------|----------|
| **Lifecycle** | Draft, published, archived states |
| **Versioning** | Immutable template versions |
| **Environments** | Staging to production promotion |
| **Variants** | Language, brand, and audience variants |

---

## Technical Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| PDF Engine | Puppeteer | Best HTML/CSS fidelity with Chrome rendering |
| API Approach | Spec-first (OpenAPI) | Ensures consistent contracts, enables SDK generation |
| Generation Model | Synchronous | Simpler implementation for MVP |
| Asset Storage | Pluggable | Support S3, PostgreSQL, filesystem for different deployments |

---

## Current Status

- **Phase**: Pre-MVP
- **Available**: Multi-tenant template CRUD, block-based visual editor
- **In Progress**: REST API design, document generation

## Contributing

See [CONTRIBUTING.md](../CONTRIBUTING.md) for contribution guidelines.

## Tracking

Development is tracked via [GitHub Issues](https://github.com/epistola-app/epistola-suite/issues) with the following labels:

- `domain: *` - Feature area (api, editor, generation, sdk, security, etc.)
- `phase: *` - Development phase (mvp, adoption, enterprise, later)
- `size: *` - Effort estimate (xs, s, m, l, xl)
