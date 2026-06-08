# Architecture Review

Date: 2026-06-08

This review focuses on how Epistola Suite's modules interact, where integration boundaries are strong or weak, and what that implies for security and maintainability.

## Executive Summary

Epistola Suite is organized as a Kotlin/Spring multi-module application with one deployable host application (`apps/epistola`) and several feature/library modules under `modules/`. The main architectural direction is sound: domain operations flow through a central mediator, authorization is declared on commands and queries, feature modules contribute routes and UI chrome through Spring beans, and the host application owns deployment concerns such as security filters, Flyway mode, Thymeleaf, actuator exposure, and resource packaging.

The strongest architectural choices are:

- A central command/query mediator with authorization enforcement before dispatch.
- Compile-time-ish authorization contracts through `Authorized` marker interfaces and a coverage test.
- Tenant-scoped identifiers and SQL queries that consistently carry tenant keys.
- A pluggable content store abstraction with PostgreSQL, S3, filesystem, and memory backends.
- Module-contributed navigation/footer/routes, which reduces host-app coupling for support features.
- Support-tier integration through explicit ports plus no-op fallbacks when the hub is disabled.

The main risks are:

- Module boundaries are implemented mostly by Gradle dependencies and Spring component scanning, not by strict package/API contracts.
- Some modules are still broad "vertical slices" that mix domain, persistence, routes, templates, schedulers, static assets, and hub adapters.
- Security is generally centralized, but several high-trust escape hatches exist: `SystemInternal`, default-on MCP, broad API key roles, local file/URL fetching, and JavaScript expression evaluation.
- Operational defaults are developer-friendly in places where production should probably be opt-in and fail-closed.
- Maintainability will degrade if module-contributed UI and background jobs keep growing without a small set of explicit extension contracts and architectural tests.

## Current Module Interaction Model

### Runtime Composition

`apps/epistola` is the integration root. It depends on the core domain, web toolkit, REST API, MCP server, editor assets, load test feature, and support modules. Spring component scanning then composes the full runtime from all packages under `app.epistola.suite`.

The app owns:

- HTTP security chains for management, API, and UI.
- Session, CSRF, CSP, exception, and request-scope filters.
- Flyway mode and migration launcher behavior.
- Thymeleaf shell integration.
- Resource packaging, including editor/design-system assets and changelog.

Feature modules contribute:

- Command/query handlers.
- Functional `RouterFunction` beans.
- Thymeleaf templates and static assets.
- Navigation/footer contributors.
- Schedulers and integration adapters.
- Module-local Flyway migrations under `classpath:db/migration`.

This gives convenient auto-registration, but it also means adding a dependency can silently add routes, jobs, migrations, and external integration behavior.

### Domain Dispatch

The domain is centered on `SpringMediator`. Commands and queries are plain messages handled by Spring-discovered handlers. Before dispatch, the mediator enforces the message's authorization contract:

- `RequiresPermission` checks tenant access and a tenant permission.
- `RequiresPlatformRole` checks a platform-level role.
- `RequiresAuthentication` requires any authenticated principal and optionally tenant access for `TenantScoped`.
- `SystemInternal` bypasses authorization.

This is a strong integration point because UI handlers, REST controllers, MCP tools, schedulers, and tests can share the same business operation and security boundary.

### UI Composition

The UI is server-rendered with Thymeleaf and HTMX. The shared `epistola-web` module provides the HTMX DSL, request helpers, multi-fragment rendering, and contribution SPIs:

- `NavContributor` and `NavMenuAggregator` build navigation per request.
- `FooterContributor` and `FooterFragmentResolver` let feature modules add footer fragments.
- `UiRequestContext` carries tenant, permission, and feature-toggle predicates.

This is a useful extension model. Support features can add their own menu items and fragments without hardcoding flags in the shell. The remaining limitation is that routes/templates are still Spring-scanned implicitly rather than declared in a module manifest.

### Support-Tier Composition

The support tier is layered around ports:

- `epistola-support` owns hub client wiring, installation state, and support overview.
- `epistola-support-snapshots` owns tenant snapshot sync.
- `epistola-support-backups` and `epistola-support-upgrading` consume snapshot sync.
- `epistola-support-feedback` owns feedback domain, UI, sync engine, and hub adapter.

The no-op fallback pattern is good because the OSS/default runtime remains functional without the hub. The risk is that feature modules still expose a lot of beans when the hub is disabled; feature toggles hide UI and schedulers may no-op, but the module is still present and active.

## Findings And Potential Resolutions

### 1. Module Boundaries Are Too Implicit

**Finding:** Module integration relies heavily on Gradle dependencies plus classpath scanning. A module can add a route, scheduler, migration, event handler, or external adapter just by being on the classpath. This works today, but it makes architectural impact hard to see in code review.

**Impact:** Future features may accidentally introduce public routes, background behavior, or Flyway migrations without an explicit integration decision in the host app.

**Potential resolutions:**

- Add a lightweight module integration manifest or Spring auto-configuration convention per feature module that declares routes, migrations, schedulers, static assets, and external adapters.
- Prefer explicit `@AutoConfiguration`/configuration imports over broad component scanning for modules that are meant to be optional.
- Add an architecture test that maps module dependencies and prevents selected packages from depending on app-layer packages.

### 2. `epistola-core` Is Doing Too Much

**Finding:** `epistola-core` contains domain models, command/query handlers, JDBI persistence, Flyway migrations, storage adapters, catalog clients, authorization contracts, feature toggles, scheduling locks, and observability support.

**Impact:** Core is stable as a central dependency, but it is becoming a large shared module. Shared modules with many responsibilities tend to become the place where unrelated concerns accumulate.

**Potential resolutions:**

- Split infrastructure concerns from pure domain contracts where it pays off: for example `epistola-core-domain`, `epistola-core-persistence`, and `epistola-core-runtime`.
- Keep command/query message types and authorization contracts in the smaller API/domain module.
- Keep JDBI handlers and concrete adapters in implementation modules.
- If a full split is too disruptive, start by creating package-level architecture tests to enforce dependency direction inside `epistola-core`.

### 3. Vertical Feature Modules Mix Multiple Layers

**Finding:** Feature modules such as `epistola-support-feedback` include domain commands/queries, persistence, migrations, static JavaScript, templates, UI routes, schedulers, and hub adapters in one module.

**Impact:** This is pragmatic for a fast-moving feature, but it makes partial reuse harder. For example, using feedback domain without feedback UI or without sync behavior is not cleanly expressible.

**Potential resolutions:**

- Introduce a repeatable vertical-slice convention with subpackages for `domain`, `application`, `persistence`, `ui`, `sync`, and `integration`.
- For larger features, split into `feature-api`, `feature-app`, `feature-ui`, and `feature-hub` modules only when there is a real reuse or deployment need.
- Add documentation for what a feature module is allowed to auto-register.

### 4. Authorization Is Strong, But `SystemInternal` Is A Broad Escape Hatch

**Finding:** The mediator correctly rejects commands and queries without an authorization marker, and tests verify coverage. However, `SystemInternal` bypasses all auth checks and is used by login flows, background jobs, API key lookup/usage, feedback sync, changelog acknowledgement, catalog editability checks, and other internals.

**Impact:** `SystemInternal` is necessary, but broad. A mistakenly marked command can bypass tenant and permission checks from any caller that can reach the mediator.

**Potential resolutions:**

- Split `SystemInternal` into narrower markers, for example `AuthBootstrapInternal`, `BackgroundJobInternal`, `ReadOnlyInternal`, and `TrustedSyncInternal`.
- Require internal commands that affect tenant data to carry a tenant key and enforce a system principal or internal caller scope.
- Add a test that inventories every `SystemInternal` message with owner/rationale metadata.
- Consider a mediator guard that only allows selected internal messages when an `InternalExecutionContext` is bound.

### 5. API Keys Grant All Tenant Roles

**Finding:** API key authentication creates a service principal with every `TenantRole` for the key's tenant. That means a key created for integration can also exercise all tenant-level permissions available through API/MCP surfaces.

**Impact:** A leaked API key has broad tenant control. This is especially important because MCP is mounted under `/api/mcp` and shares the API security chain.

**Potential resolutions:**

- Add scopes/permissions to API keys and map them to a reduced permission set.
- Default new keys to generation/API use only, then require explicit opt-in for management actions.
- Show key scope in the UI and audit log.
- Add tests proving scoped keys cannot access template management, tenant settings, or MCP tools outside their scope.

### 6. MCP Is Default-On

**Finding:** `epistola.mcp.enabled` defaults to `true`, and the Spring AI MCP server is mounted at `/api/mcp`. It is protected by the API chain, but it exposes a powerful assistant-oriented surface across templates, catalogs, content, previews, and other read/write tools.

**Impact:** Default-on assistant endpoints increase attack surface and operational surprise, especially in production deployments where API keys may have broad tenant roles.

**Potential resolutions:**

- Default MCP to disabled in `application.yaml`, then enable it explicitly in local/demo profiles.
- Add a production startup warning or fail-fast guard when MCP is enabled without an explicit production property.
- Give MCP its own API-key scope or filter chain, rather than relying on generic tenant-wide API keys.
- Document MCP exposure in deployment/security docs.

### 7. CSP Still Allows Inline Scripts And External Script CDN

**Finding:** The UI security chain sets a CSP, but `script-src` includes `'unsafe-inline'` and `https://cdn.jsdelivr.net`; `style-src` also includes `'unsafe-inline'`.

**Impact:** Inline scripts/styles weaken the CSP's ability to contain XSS. The external script source adds a supply-chain dependency unless all scripts are vendored and pinned.

**Potential resolutions:**

- Continue moving HTMX behavior to external vendored scripts and server-rendered attributes.
- Replace inline scripts with nonces or hashes where inline remains unavoidable.
- Remove `cdn.jsdelivr.net` if assets are already self-hosted, or pin exact integrity-managed resources.
- Add a CSP regression test for critical pages.

### 8. Local File And URL Fetching Need Stronger Operational Boundaries

**Finding:** `CatalogClient` and `CodeListClient` support `classpath:`, `file:`, and `https:` URLs, with optional `http:`. They validate extensions and block simple `..` file paths, but they do not restrict file reads to configured directories or restrict HTTPS targets against SSRF-sensitive networks.

**Impact:** Users with catalog/code-list management permissions can cause the server to fetch local files ending in `.json` or call arbitrary HTTPS endpoints. This may be acceptable in trusted self-hosted environments, but it is risky for multi-tenant SaaS or shared admin environments.

**Potential resolutions:**

- Disable `file:` URLs outside local/dev profiles unless explicitly enabled.
- Restrict file URLs to configured allowlisted base directories and normalize paths before checking containment.
- Add outbound HTTP allowlists or block private/link-local/metadata IP ranges after DNS resolution.
- Apply response size and timeout limits for remote catalog/code-list fetches.

### 9. JavaScript Expression Evaluation Lacks An Enforced Time Limit

**Finding:** `JavaScriptEvaluator` documents execution time limits, but the GraalJS context creation only disables host/native/process/thread/environment access. No obvious execution timeout is enforced in that class. JSONata does set runtime bounds.

**Impact:** A malicious or accidental JavaScript expression can consume CPU during preview or generation. In batch generation this can become an availability problem.

**Potential resolutions:**

- Add an enforced timeout around JavaScript evaluation, either through cancellable execution or an engine-supported interrupt strategy.
- Consider disabling JavaScript expressions by default for untrusted tenants and prefer JSONata/simple path.
- Add tests for infinite loops and expensive expressions.
- Expose expression language policy per installation or tenant.

### 10. Module-Contributed UI Needs Collision And Visibility Tests

**Finding:** Navigation/footer contribution is a good direction, but contributors identify groups and sections by strings. Routes and templates are also contributed by classpath convention.

**Impact:** Duplicate section keys, route collisions, or template name collisions can be introduced silently as feature modules grow.

**Potential resolutions:**

- Add tests that collect all `NavContributor` output under representative contexts and assert unique group/section keys.
- Add route inventory tests for duplicate paths and method conflicts.
- Prefix feature templates/static assets consistently by module.
- Introduce typed feature keys and route constants for shared destinations.

### 11. Feature Toggles Are Used For UI Visibility More Than Bean Activation

**Finding:** Tenant feature toggles control whether UI items are emitted and whether schedulers process a tenant, but many feature beans still exist when toggles are disabled.

**Impact:** Disabled features still add code paths, routes, templates, and sometimes background checks to the runtime. This is usually fine, but it can surprise operators who expect a disabled feature to disappear entirely.

**Potential resolutions:**

- Separate tenant feature toggles from installation-level module activation.
- Add installation-level `@ConditionalOnProperty` guards for whole feature surfaces where appropriate.
- Keep tenant toggles for per-tenant visibility/behavior after the module is explicitly active.

### 12. Generated Assets Are Build-Time Coupled

**Finding:** The app build copies editor/design-system/HTMX assets and verifies editor/dist and vendored HTMX output. This is reproducible, but it creates a tight coupling between backend resource processing and frontend package installation.

**Impact:** Backend builds can fail for missing frontend artifacts even when touching only backend code. That may be intentional for release builds, but it is expensive for local iteration and CI partitioning.

**Potential resolutions:**

- Keep strict verification for release/package tasks.
- Add a documented backend-only test/build path that does not package frontend resources.
- Make CI jobs explicit: frontend build, backend test, package image.

## Security Assessment

Overall, security architecture is better than average for an application of this size because authorization is centralized in the mediator and domain messages must declare their auth model. Tenant isolation is consistently represented in IDs and SQL, and there are dedicated tests for authorization coverage and tenant isolation.

The areas needing the most attention are exposure control and least privilege:

- MCP should be opt-in in production.
- API keys should be scoped.
- `SystemInternal` should be narrowed or context-bound.
- Local file and remote URL fetching should have environment-specific restrictions.
- JavaScript expression execution should have an enforced timeout.
- CSP should move away from inline allowances.

## Maintainability Assessment

The codebase is maintainable today, but the architecture is at the point where implicit Spring composition can become hard to reason about. The next maintainability improvements should focus less on refactoring for elegance and more on making integration effects visible:

- Architectural tests for dependency direction and module auto-registration.
- Explicit module activation conventions.
- A documented feature-module template.
- Smaller core boundaries, starting with package-level rules if module splits are too disruptive.
- Route/nav/template collision tests.

## Recommended Priority Order

1. Make MCP opt-in for production and introduce API-key scopes.
2. Add enforced timeout tests/fixes for JavaScript expressions.
3. Restrict `file:` and outbound URL fetching by environment and allowlist.
4. Inventory and narrow `SystemInternal` usage.
5. Add architecture tests for module dependencies, route collisions, and UI contribution uniqueness.
6. Document a feature-module integration contract.
7. Gradually split or internally enforce boundaries inside `epistola-core`.

## Suggested Architecture Tests

- Every production command/query implements `Authorized` and every `SystemInternal` message has a declared rationale.
- No module under `modules/` depends on `apps/epistola`.
- UI feature modules may depend on `epistola-web`, but `epistola-web` may only depend on core/domain-level APIs.
- Route paths are unique by HTTP method.
- Navigation group and section keys are unique.
- Feature modules do not register schedulers unless guarded by an installation-level property or advisory lock where multi-pod execution matters.
- CSP policy does not regress by reintroducing new external script hosts.

## Closing View

Epistola's architecture has a solid center: a shared domain dispatch model, tenant-aware IDs, and a single authorization gate. The next step is to make module integration more explicit and fail-closed. That will preserve the current plugin-like extensibility while reducing the chance that a new module quietly expands the runtime surface area.
