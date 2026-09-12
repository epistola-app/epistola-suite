# apps/epistola

The deployable app and the UI host: Thymeleaf templates, HTMX handlers and routes, security,
bootstrap, the migration launcher, observability and the page chrome (`layout/shell`, `layout/nav`,
`fragments/*`). Business logic belongs in `epistola-core`, not here.

It also hosts the repo-wide guard tests, under `src/test/kotlin/app/epistola/suite/architecture/`.

## Two endpoint layers that never mix

|            | REST API                                       | UI handlers                             |
| ---------- | ---------------------------------------------- | --------------------------------------- |
| Path       | `/api/**`                                      | `/tenants/**`, `/themes/**`, …          |
| Auth       | API key or OAuth2 JWT, stateless, no CSRF      | session                                 |
| Built from | `@RestController` in `modules/rest-api`        | `@Component` + functional routing here  |
| Returns    | JSON DTOs (`application/vnd.epistola.v1+json`) | Thymeleaf, HTMX fragments, minimal JSON |
| For        | external systems; stable and versioned         | this UI; free to change                 |

**UI code — Thymeleaf, JavaScript, TypeScript — must never call `/api/**`.** A UI need gets a UI
route. `UiRestApiSeparationTest` is the guard.

## Handlers and routes

Copy `handlers/EnvironmentHandler.kt` and `handlers/EnvironmentRoutes.kt`: a `@Configuration class
<Domain>Routes` exposing a `RouterFunction<ServerResponse>` bean built with
`router { "/tenants/{tenantId}/…".nest { … } }`, and a `@Component` handler taking a `ServerRequest`.

- Read the tenant with `request.tenantId()`; gate with `requirePermission(tenantId.key, Permission.X)`.
- Reach the domain through `.execute()` / `.query()`. Handlers do not touch JDBI.
- Full page: `ServerResponse.ok().page("domain/list") { … }`. Swap:
  `request.htmx { fragment("domain/list", "rows") { … } }`. Prefer `onFullPage {}` over the older
  `onNonHtmx {}` in new code.
- Dialog forms: `request.form { field("name") { required(); maxLength(100) } }`, then
  `form.executeOrFormError { … }`, `dialogFieldErrors(...)` on failure and `dialogSuccess(...)` on
  success. See [`docs/dialog-forms.md`](../../docs/dialog-forms.md) and
  [`docs/htmx.md`](../../docs/htmx.md).

Markup rules — strict CSP, design-system classes, icons, page headers, input lengths — are in
[`.agents/rules/ui-markup.md`](../../.agents/rules/ui-markup.md).

## Feature modules contribute UI

A feature module may ship its own handlers and `templates/**`; Spring merges classpath templates
from every JAR, and CSP and security wiring apply automatically. This app stays the host that
composes them, through the SPIs in `epistola-web`: `NavContributor` (nav groups and items, filtered
by permission and toggles, merged by `NavMenuAggregator`), `FooterContributor`,
`HomeNoticeContributor` and `FragmentModelContributor`. A contributor that needs feature state reads
it through `ResolveFeatureToggles(tenantKey)`, never by injecting a service.

Feature-module templates currently depend on a few host-only fragments (`fragments/confirm-dialog`,
`fragments/search`) — treat those as a host contract until they move to `epistola-web` `(unenforced)`.

## Verify

```bash
pnpm build   # once; the app packages the editor bundle
./gradlew :apps:epistola:integrationTest --tests "*YourHandlerHtmxTest*"
./gradlew :apps:epistola:unitTest --tests "app.epistola.suite.architecture.*"
./gradlew :apps:epistola:uiTest --tests "*YourUiTest*"
```
