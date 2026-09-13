---
paths:
  - "**/templates/**/*.html"
  - "**/static/**/*.js"
  - "**/static/**/*.css"
---

# Markup and browser behaviour

A strict Content Security Policy is enforced on every UI response (`SecurityConfig.kt`):
`script-src 'self'` — no `'unsafe-inline'`, no `'unsafe-eval'`, no external origins
([ADR 0010](../../docs/adr/0010-strict-script-src-csp.md)). `CspTemplateComplianceTest` fails the
build, and a CSP violation in the browser console fails a UI test.

- **No executable inline `<script>` anywhere.** Behaviour lives in a static JS file —
  `apps/epistola/src/main/resources/static/js/`, or a feature module's own `static/` directory. The
  shell uses `hx-boost="true"`, so navigation is an HTMX body swap and per-page `<head>` scripts
  never reload; app-wide behaviours are registered in `fragments/htmx.html`.
- **No inline `on*=` attributes** (`onclick=`, `onchange=`, …) and no `hx-on::*` / `hx-on-*`, which
  `eval()`. Instead put a `data-*` hook on the element and add a **delegated listener** on
  `document` in static JS — installed once, HTMX events bubble, so it works for markup present at
  load and for anything swapped in later.
- Generic hooks already exist in `static/js/behaviors.js` — `data-open-dialog`, `data-close-dialog`,
  `data-confirm-url`, `data-confirm-submit`, `data-reset-on-success`, `data-copy-source` — reuse
  before inventing. Note `data-open-dialog` is a _click_ trigger; a dialog arriving by HTMX swap is
  opened by `data-dialog-mount` on the container.
- **Server data reaches JS as inert JSON**, never as code: `<script type="application/json"
id="…" th:inline="javascript">` parsed from static JS, with initialization driven by `htmx:load`
  (it fires for the first page and every swap — guard with a `data-…-mounted` attribute). Small
  values can ride `data-*` attributes.
- `style-src` still allows `'unsafe-inline'`, deliberately, so inline `style=` is fine.

## The other markup guards

- Use design-system classes; `DesignSystemClassTest` rejects invented ones
  ([`docs/brandguide.md`](../../docs/brandguide.md)).
- Icons must exist in the sprite (`IconUsageTest`), via `epistola-web/icon`.
- Page headers go through `epistola-web/page-header` with valid arguments (`PageHeaderUsageTest`).
- Text inputs carry `maxlength` matching the domain limit (`InputMaxLengthTest`).
- UI must never call `/api/**` (`UiRestApiSeparationTest`).

## Verify

```bash
./gradlew :apps:epistola:unitTest --tests "app.epistola.suite.architecture.*"
```
