# Project Context

## Purpose

Epistola Suite is a document generation platform enabling users to create, manage, and generate professional documents (PDF and HTML) from templates with dynamic data binding. It serves both developers (via REST API and SDKs) and business users (via a visual template editor).

## Tech Stack

### Backend
- **Language**: Kotlin 2.3.0 on JDK 25
- **Framework**: Spring Boot 4.0.0
- **Database**: PostgreSQL with JSONB for template storage
- **Data Access**: JDBI (not JPA)
- **JSON**: Jackson 3 (`tools.jackson.*`)
- **View Engine**: Thymeleaf + HTMX

### Frontend
- **Server-side**: Thymeleaf templates with HTMX for dynamic interactions
- **Client Components**: Vite + TypeScript + React 19 for rich interactive editors
- **State Management**: Zustand + Immer
- **Rich Text**: TipTap 3

### Infrastructure
- **Build**: Gradle (Kotlin DSL) + pnpm workspaces
- **Testing**: JUnit 5, Testcontainers (requires Docker)
- **Linting**: ktlint for Kotlin, ESLint for TypeScript

## Project Conventions

### Code Style

**Kotlin:**
- Follow ktlint rules (enforced by CI)
- Run `gradle ktlintFormat` after changes
- Use data classes for DTOs and domain models
- Prefer immutability

**TypeScript:**
- Use TypeScript strict mode
- Prefer functional components with hooks
- Use Zustand for state management

### Architecture Patterns

**Backend:**
- Command/Query Bus (Mediator pattern) for all state changes
- Functional routing (Spring WebMvc.fn) instead of annotated controllers
- JDBI with SQL queries (no ORM)
- Multi-tenant: All queries filter by `tenant_id`

**Frontend:**
- Server-side rendering with Thymeleaf for navigation
- Client modules (React) only for rich interactive components
- Import maps for shared dependencies across modules

### Testing Strategy

- Integration tests with Testcontainers PostgreSQL
- TestFixture DSL for BDD-style setup
- All tests require Docker running
- Run `gradle test` before committing

### Git Workflow

- Conventional Commits: `feat:`, `fix:`, `docs:`, `chore:`, `refactor:`, `test:`, `ci:`
- Breaking changes: `feat!:` or `BREAKING CHANGE:` in footer
- Small, focused commits
- Update CHANGELOG.md for notable changes

## Domain Context

### Core Concepts

- **Tenant**: Organizational unit for multi-tenancy isolation
- **DocumentTemplate**: Contains visual layout (TemplateModel), input schema (dataModel), and examples
- **TemplateModel**: Block-based visual structure (text, containers, conditionals, loops, columns, tables)
- **Block**: Atomic visual element that can be nested and styled
- **Expression**: Dynamic value reference using `{{path.to.value}}` syntax
- **DataModel**: JSON Schema defining expected input parameters

### Document Generation Flow

1. Client sends template ID + data payload
2. Server validates data against template's JSON Schema
3. Server evaluates expressions in TemplateModel with provided data
4. Server renders to HTML
5. (For PDF) Puppeteer converts HTML to PDF

## Important Constraints

- **Pre-production**: Breaking changes acceptable without migration paths
- **No Native Image**: Currently JVM-only due to JDBI/Kotlin reflection issues
- **Docker Required**: Tests use Testcontainers
- **Jackson 3**: Use `tools.jackson.*` imports, not `com.fasterxml.jackson`

## External Dependencies

- **Keycloak**: Planned for enterprise authentication (Phase 2)
- **S3-compatible storage**: For document and asset storage (MinIO for self-hosted)
- **Open Notificaties**: Event publishing for GZAC integration (Phase 3)
