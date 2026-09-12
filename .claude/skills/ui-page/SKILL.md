---
name: ui-page
description: Add or change a server-rendered UI page, HTMX fragment or dialog form in the suite. Use when building a list page, a create/edit dialog, or a handler route under /tenants/**.
---

UI is Thymeleaf rendered server-side, driven by HTMX. Handlers are plain `@Component` classes taking
a `ServerRequest`; routes are a functional router. A strict CSP and several markup guards apply.

## Exemplars — copy these, don't copy this file

| For                                                     | Read                                                                              |
| ------------------------------------------------------- | --------------------------------------------------------------------------------- |
| A list page, dialog form, search and delete, end to end | `apps/epistola/src/main/kotlin/app/epistola/suite/handlers/EnvironmentHandler.kt` |
| Route declaration                                       | `apps/epistola/src/main/kotlin/app/epistola/suite/handlers/EnvironmentRoutes.kt`  |
| The DSL itself                                          | `modules/epistola-web/src/main/kotlin/app/epistola/suite/htmx/HtmxDsl.kt`         |
| Dialog lifecycle                                        | [`docs/dialog-forms.md`](../../../docs/dialog-forms.md)                           |
| The DSL guide                                           | [`docs/htmx.md`](../../../docs/htmx.md)                                           |
| Colors, spacing, components                             | [`docs/brandguide.md`](../../../docs/brandguide.md)                               |

## The shape

- **Routes** live in a `@Configuration class <Domain>Routes(private val handler: …)` exposing a
  `RouterFunction<ServerResponse>` `@Bean` built with `router { "/tenants/{tenantId}/…".nest { … } }`.
  No `@Controller`, no `@GetMapping` — those belong to the REST API only.
- **Handlers** read the tenant with `request.tenantId()` (never hand-rolled path parsing), gate with
  `requirePermission(tenantId.key, Permission.X)`, and reach the domain through `.execute()` /
  `.query()`. Don't touch JDBI from a handler.
- **Responses**: `ServerResponse.ok().page("domain/list") { … }` for a full page;
  `request.htmx { fragment("domain/list", "rows") { … } }` for a swap. Prefer `onFullPage {}` over
  the older `onNonHtmx {}` in new code.
- **Forms**: build with `request.form { field("name") { required(); maxLength(100) } }`, run the
  command through `form.executeOrFormError { … }`, then render errors with `dialogFieldErrors(...)`
  and success with `dialogSuccess(...)`. Field errors and command failures share one error path.
- **Markup rules** (a strict CSP, ADR 0010): no inline `<script>`, no `on*=` attributes, no
  `hx-on::*`. Behaviour goes in a static JS file with a delegated listener on a `data-*` hook; reuse
  the hooks in `apps/epistola/src/main/resources/static/js/behaviors.js` before inventing one.
  Use the shared `epistola-web/page-header`, `epistola-web/icon` and `epistola-web/dialog` fragments,
  design-system classes, and `maxlength` on text inputs.
- **Never call `/api/**` from UI code.** The REST API is for external systems; add a UI route instead.

## Steps

1. Add or extend the handler next to its neighbours, then wire the route in the `*Routes.kt` file.
2. Add the Thymeleaf template under `apps/epistola/src/main/resources/templates/<domain>/`.
3. Put any behaviour in a static JS file as a delegated listener.
4. Write a `*HandlerHtmxTest` for the server contract; add a Playwright `*UiTest` only for behaviour
   that genuinely needs a browser (see the `tests` skill).

## Verify

```bash
./gradlew :apps:epistola:integrationTest --tests "*YourHandlerHtmxTest*"
./gradlew :apps:epistola:unitTest --tests "app.epistola.suite.architecture.*"
```

The guards that will fail you: `CspTemplateComplianceTest`, `DesignSystemClassTest`, `IconUsageTest`,
`PageHeaderUsageTest`, `InputMaxLengthTest`, `UiRestApiSeparationTest`.
