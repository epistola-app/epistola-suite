# Iframe embedding + postMessage bridge

Epistola's web UI can be embedded in an `<iframe>` on a trusted host page and
driven from there via `postMessage`. This is the **basis** for a future
training facility on epistola.app that launches exercises inside an embedded
Epistola — epistola-suite ships none of that training content itself, only the
plumbing that lets a host page embed and talk to it. See
[ADR 0015](adr/0015-iframe-embedding-bridge.md) for the full design rationale.

## Status

- **Demo-mode only.** Every capability described here is off by default and
  only ever turned on by the `demo` and `local` Spring profiles
  (`application-demo.yaml`, `application-local.yaml` — the latter so the
  training-facility site can be developed against a local Epistola instance,
  allowlisting `http://localhost:4321`, its usual local dev port). Self-hosted
  customer deployments are byte-for-byte unaffected.
- **No training content ships here.** No exercises, no lesson data, no
  training-specific UI — only the embedding basis (framing + the message
  protocol below).
- **REST API access needs no changes.** Creating tenants and reading resources
  over the REST API already works for an external caller and isn't part of
  this feature.

## Configuration

```yaml
epistola:
  embedding:
    enabled: false # true in application-demo.yaml and application-local.yaml
    allowed-parent-origins: [] # e.g. [https://epistola.app] or [http://localhost:4321] for local dev
```

`EmbeddingProperties` (`apps/epistola/.../embedding/EmbeddingProperties.kt`).
Turning `enabled` on changes three things at once, all conditioned on the same
flag:

1. **CSP `frame-ancestors`** becomes the space-joined `allowed-parent-origins`
   list instead of `'none'`, and Spring Security's default
   `X-Frame-Options: DENY` is explicitly disabled (CSP `frame-ancestors` is
   authoritative over it in every modern browser and, unlike XFO, supports an
   origin list).
2. **Session (`sid`) and CSRF (`XSRF-TOKEN`) cookies** switch from
   `SameSite=Lax` to `SameSite=None; Secure`. Required for either cookie to
   reach the server at all from inside a cross-origin iframe — "site for
   cookies" is computed against the top-level document, so a `Lax` cookie is
   dropped on every request the embedded page's own JS/htmx makes back to its
   own origin. See ADR 0015 for why this is scoped this narrowly.
3. **The bridge script and its config JSON island** are included in the
   rendered shell at all (`fragments/htmx.html`, `layout/shell.html`) —
   entirely absent from the page when embedding is off.

## The message protocol

Every message carries a `source` so both sides can tell it apart from other
`postMessage` traffic on the page. The bridge never sends with `"*"` as
`postMessage` target origin, and validates every inbound message's
`event.origin` (against `allowed-parent-origins`) and `event.source ===
window.parent` before acting on it.

### Host → suite: `navigate`

```jsonc
{
  "source": "epistola-host",
  "type": "navigate",
  "resource": {
    "resourceType": "template",
    "tenantId": "acme",
    "catalogKey": "default",
    "key": "invoice",
  },
}
```

The host supplies a **typed resource identity only, never a URL** — this is
deliberate: allowing the host to hand over a raw URL would let it dictate an
arbitrary destination (open-redirect-shaped risk). `embed-bridge.js` runs the
identity through a small, closed lookup (`template → templates`,
`theme → themes`, `stencil → stencils`), validates each identifier against a
slug-format check, and builds one of exactly three URL shapes. Navigation is
then performed via `htmx.ajax(...)` — the same boosted-navigation path an
in-app `<a>` click takes — so the destination page's normal permission and
existence checks run exactly as usual. There is no separate "trusted host
navigation" path that could diverge or skip a check.

An unrecognized `resourceType` or a malformed identifier is silently ignored.

### Suite → host: `navigated`

```jsonc
{
  "source": "epistola-suite",
  "type": "navigated",
  "path": "/tenants/acme/templates/default/invoice",
  "resource": {
    "resourceType": "template",
    "tenantId": "acme",
    "catalogKey": "default",
    "key": "invoice",
  }, // or null
}
```

Fired on every real navigation — the initial load, every boosted (`hx-boost`)
swap, and browser back/forward — whether triggered by the host's `navigate`
message or by the user clicking around inside the iframe. `resource` is `null`
on pages that aren't a single resource's detail view (lists, dashboards).

The resource identity comes from a per-page JSON island
(`#epistola-current-resource`) that `EmbeddingContextInterceptor` populates by
matching the request path against
`/tenants/{tenantId}/(templates|themes|stencils)/{catalogKey}/{key}` — not by
threading a model attribute through each detail handler. The URL shape is
already the shared identity scheme (identical to the REST API's), so this one
regex covers every resource-detail route today, and any sub-tab path
underneath it, without a per-handler edit.

### Suite → host: `resource-changed`

```jsonc
{
  "source": "epistola-suite",
  "type": "resource-changed",
  "resource": {
    "resourceType": "theme",
    "tenantId": "acme",
    "catalogKey": "default",
    "key": "brand",
  },
  "operation": "create", // | "update" | "delete"
}
```

Fired whenever a create/update/delete succeeds. No content payload yet —
identity and operation only — but the shape leaves room for one later.

`resourceType` values are `"template" | "theme" | "stencil"` in v1 (additive
later), matching `CatalogResourceType.wireName`
(`modules/epistola-core/.../catalog/graph/ResourceGraph.kt`).

## How `resource-changed` detection works

This is **entirely client-side** — no Kotlin/backend involvement, and
deliberately so. An earlier version of this design added an explicit
`HtmxResponseBuilder.trigger(...)` call to every mutating handler's success
path (~8 call sites across three handler files). That coupled every
template/theme/stencil handler — and every future one — to the embedding
feature, was easy to forget when adding a new mutating handler, and used three
inconsistent transports for one conceptual signal. It was replaced with a
single generic listener in `embed-bridge.js`.

Template/theme/stencil mutations already follow one uniform URL convention end
to end (`DocumentTemplateRoutes`/`ThemeRoutes`/`StencilRoutes`), so a generic
`htmx:afterRequest` listener classifies every create/update/delete from the
request (and, for create, the response) alone:

1. **`PATCH /tenants/{t}/{type}/{catalogKey}/{key}(/…)?`** → `update`. Identity
   comes straight from the request path.
2. **`POST /tenants/{t}/{type}/{catalogKey}/{key}/delete`** → `delete`.
   Identity from the request path.
3. **`POST /tenants/{t}/{type}`** (the bare collection root) → `create`.
   Identity comes from the response's `HX-Location`/`HX-Redirect` header — the
   same header `dialogLocation`/`dialogRedirect` (`HtmxDsl.kt`) already set for
   unrelated, pre-existing navigation reasons, read via
   `xhr.getResponseHeader(...)` inside the `htmx:afterRequest` handler.

Any current or future handler that follows this URL shape is covered
automatically — nothing to wire, nothing to keep in sync. Two mutations aren't
observable this way and each get one small, explicit exception instead of
forcing the general mechanism to cover them:

- **Raw `fetch()` from static JS** bypasses htmx.js entirely, so
  `htmx:afterRequest` never fires for it. That call site invokes
  `window.epistolaEmbedBridge?.notifyResourceChanged(resource, operation)`
  directly after a successful response (the `?.` makes it a no-op when the
  bridge isn't loaded, i.e. embedding disabled). See `theme-editor-boot.js`'s
  `onSave` for the worked example.
- **A genuine browser-followed redirect** (`hx-boost="false"` on the form,
  `form.submit()`) has no client-observable request/response at all — no XHR,
  no htmx event, nothing. The handler appends a one-shot
  `?resourceDeleted=type:catalogKey:key` query param to the redirect
  `Location` instead; `embed-bridge.js` reads it once on load, fires the
  notification, then strips it via `history.replaceState` so a refresh
  doesn't re-fire it. See `DocumentTemplateHandler.delete` for the worked
  example.

v1 covers template, theme, and stencil create/update/delete — the three
catalog resource types with existing detail pages. `StencilHandler.update()`
(the plain PATCH name/description/tags endpoint) currently has **no live UI
caller** anywhere (no `hx-patch` markup, no `fetch()` call), so no mutation
ever reaches it to classify — nothing to do until a real caller exists.
Extending coverage to a resource type that follows the same URL convention
needs no change at all; one that doesn't needs a small addition to
`embed-bridge.js`'s URL pattern, not a new per-handler Kotlin call.

## Non-goals

- No REST API or MCP surface changes — this is UI-only.
- No per-tenant `FeatureToggleService`/`KnownFeatures` entry — embedding is
  install-wide boot-time configuration, not DB-backed per-tenant state.
- No training material, exercises, or lesson data of any kind in
  epistola-suite.
