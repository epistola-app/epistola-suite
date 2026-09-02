# ADR 0015: Iframe embedding + postMessage bridge (demo-mode only)

- **Status:** Accepted
- **Date:** 2026-08-25
- **Deciders:** Epistola team
- **Tags:** security, csp, cookies, embedding, demo, frontend

Note: this ADR's "bridge" is unrelated to ADR 0011's SaaS-to-client bridge (a
server-to-server integration for customer-hosted deployments talking to a remote
SaaS). This ADR is about a browser embedding Epistola's own web UI in an
`<iframe>` and driving it via `postMessage`. The two documents happen to share a
word, nothing else.

## Context

A future "training facility" on epistola.app will launch exercises by embedding
Epistola's web UI in an `<iframe>` and driving it via `postMessage`. Epistola-suite
must ship the basis for that — the ability to be framed, and a small message
protocol — without shipping any training content itself, and only ever in
demo-mode/profile deployments. Every other deployment (self-hosted customer
installs in particular) must be unaffected.

Three capabilities were requested:

1. Allow framing at all, but only when embedding is explicitly turned on.
2. Host → suite: navigate to a page by resource identity, never by raw URL.
3. Suite → host: notify on every navigation, and on every resource
   create/update/delete (identity + operation only, no content yet).

Today's `SecurityConfig.kt` sets `frame-ancestors 'none'` unconditionally, and
Spring Security's default `X-Frame-Options: DENY` is never overridden — the app
cannot be framed by any origin, anywhere, full stop.

## Decision

### Framing is gated by one new install-wide property set, not a feature toggle

`epistola.embedding.enabled` + `epistola.embedding.allowed-parent-origins`
(`EmbeddingProperties`, a `@ConfigurationProperties` bean) — off everywhere
except `application-demo.yaml` (allowlisting `https://epistola.app`) and
`application-local.yaml` (allowlisting `http://localhost:4321`, so the
training-facility site can be developed against a local Epistola instance).
This deliberately does **not** use the per-tenant
`FeatureToggleService`/`KnownFeatures` system: that system is DB-backed and
resolved per tenant, but embedding is an environment-level decision (a
`demo`-profile install can have multiple tenants, and framing shouldn't vary
between them) — the same shape as the existing `epistola.demo.enabled`, just
carrying a list value in addition to the flag, and read from more than one
unrelated place (`SecurityConfig`, `SessionConfig`, `EmbeddingContextInterceptor`).

### CSP `frame-ancestors` and `X-Frame-Options`

`frame-ancestors` becomes the joined `allowedParentOrigins` list when embedding
is enabled and the list is non-empty; otherwise it stays `'none'`, byte-identical
to today. `X-Frame-Options` (Spring Security's default `DENY`) is only explicitly
disabled when embedding is on — `frame-ancestors` is authoritative over it in
every modern browser and (unlike `X-Frame-Options`) supports an origin list, so
leaving `DENY` in place under embedding would just contradict the CSP header for
no protective benefit.

### Cookies are relaxed for potentially cross-site allowlisted hosts

Session (`sid`) and CSRF (`XSRF-TOKEN`) cookies are `SameSite=Lax` by default. A
request's "site for cookies" is computed against the top-level document, but
site is not the same as origin: scheme and registrable domain determine
same-site status, while port does not. The configured production subdomains and
local localhost ports are therefore cross-origin but same-site. The original
claim that their iframe requests necessarily exclude Lax cookies was incorrect.
The normative algorithm is in the
[cookie specification](https://datatracker.ietf.org/doc/html/draft-ietf-httpbis-rfc6265bis/#section-5.2.1).

Both cookies nevertheless switch to `SameSite=None; Secure` — but **only** when
`epistola.embedding.enabled=true`. `SessionConfig`'s `cookieSerializer()` branches
explicitly rather than always calling `setUseSecureCookie(embeddingProperties.enabled)`:
Spring Session's own default for `Secure` mirrors `HttpServletRequest.isSecure()`,
so unconditionally passing `false` would wrongly strip `Secure` from an HTTPS
deployment that never asked for embedding. The flag is only ever forced on, never
forced off. The CSRF side keeps `.spa()`'s `SpaCsrfTokenRequestHandler` (a
package-private class, not replaceable directly) and overrides only the token
repository afterward, via `CsrfConfigurer.csrfTokenRepository(...)`, to a
`CookieCsrfTokenRepository.withHttpOnlyFalse()` with
`setCookieCustomizer { it.sameSite("None").secure(true) }`.

This permits genuinely cross-site origins if they are later allowlisted. It is a
real defense-in-depth reduction (`Lax` → `None`), explicitly scoped to embedding;
it is not required solely by the two currently configured host relationships.
Every other profile keeps today's behavior unchanged.

### Navigation is identity-based, resolved client-side, never a raw URL

The host can only ever send `{ resourceType, tenantId, catalogKey, key }`. The
bridge script (`embed-bridge.js`) runs that identity through a small, closed
lookup table (`template → templates`, `theme → themes`, `stencil → stencils`)
plus a slug-format check on each identifier, and builds one of exactly three URL
shapes — there is no code path that turns arbitrary host input into an arbitrary
path. The resulting navigation is then performed via `htmx.ajax(...)`, the same
boosted-navigation path an in-app `<a>` click takes, so the destination page's
normal server-side permission and existence checks run exactly as usual. This was
chosen over a new server-side "resolve identity → path" endpoint because the
destination page's existing handler logic already **is** the enforcement point —
an endpoint would only duplicate it.

### Resource mutation notifications are derived client-side, not emitted by Kotlin

An earlier version of this design added an explicit `HtmxResponseBuilder.trigger(...)`
call to every mutating handler's success path — one Kotlin call site per
create/update/delete, ~8 in total. That was rejected during implementation
review: it coupled every template/theme/stencil handler (and every future one)
to the embedding feature, required a developer to remember to add the call when
writing a new mutating handler, and used three inconsistent transports for what
is conceptually one signal.

Instead, `embed-bridge.js` derives `resource-mutated` from a single generic
`htmx:afterRequest` listener, because template/theme/stencil mutations already
follow one uniform URL convention end to end
(`DocumentTemplateRoutes`/`ThemeRoutes`/`StencilRoutes`):

- `PATCH /tenants/{t}/{type}/{catalogKey}/{key}(/…)?` → update.
- `POST /tenants/{t}/{type}/{catalogKey}/{key}/delete` → delete.
- `POST /tenants/{t}/{type}` (the bare collection root) → create — the created
  identity is read straight off the same `HX-Location`/`HX-Redirect` response
  header that `dialogLocation`/`dialogRedirect` already set for unrelated,
  pre-existing navigation reasons, via `xhr.getResponseHeader(...)`.

This needs zero backend code: any new resource type or handler that follows the
same URL convention is covered automatically, with no per-handler edit and
nothing to keep in sync. Two mutations aren't observable this way and keep one
small, explicit exception each:

- Raw `fetch()` saves bypass htmx.js entirely, so no
  `htmx:afterRequest` fires for them — that call site invokes
  `window.epistolaEmbedBridge?.notifyResourceMutated(...)` directly.
- A genuine browser-followed redirect (template's `hx-boost="false"` delete
  form) has no client-observable request/response at all — the handler appends
  a one-shot `?resourceDeleted=type:catalogKey:key` flash query param to the
  redirect target instead, consumed once by the bridge script and then
  stripped via `history.replaceState`.

v1 coverage is template, theme, and stencil create/update/delete (the three
catalog resource types with existing detail pages) — any future resource type
following the same URL shape is covered for free; one that doesn't needs a
small addition to the bridge's URL pattern, not a new per-handler call. See
`docs/embedding.md` for the full decision tree, including the two exceptions
above.

### Where the resource identity for `navigated` comes from

Client-side only, same as `resource-mutated`: `embed-bridge.js` runs
`location.pathname` through the same `parseResourcePath` matcher used for
mutation classification. An earlier version had `EmbeddingContextInterceptor`
derive this server-side (matching the request path against
`/tenants/{tenantId}/(templates|themes|stencils)/{catalogKey}/{key}` and
rendering it into a `#epistola-current-resource` JSON island) — removed once
it became clear the client already had the exact same URL-parsing logic for
`resource-mutated`, so there was nothing left for the server to compute that
the client couldn't derive itself. `EmbeddingContextInterceptor` now only
carries `embeddingEnabled`/`allowedParentOrigins`, which genuinely are
server-side config with no client-derivable equivalent.

## Consequences

- Embedding is entirely off by default; the only profiles that turn it on today
  are `demo` (`allowed-parent-origins: [https://epistola.app]`) and `local`
  (`allowed-parent-origins: [http://localhost:4321]`, for developing the
  training-facility site against a local Epistola instance).
- `SecurityConfig`, `SessionConfig`, and `WebMvcConfig` all now depend on
  `EmbeddingProperties`. None of their behavior changes when it's disabled.
- A new `docs/embedding.md` documents the message protocol and the extension
  pattern for adding `resource-mutated` coverage to more handlers.
- No REST API or MCP surface changes — this is UI-only.
- No training material of any kind ships in epistola-suite.

## Related

- ADR 0010 — the strict `script-src` CSP this change's `frame-ancestors` and
  bridge script must keep complying with (no inline scripts, no `on*=`
  handlers — `embed-bridge.js` is an ordinary static file, JSON islands for
  server data).
- ADR 0011 — an unrelated ADR that also uses the word "bridge"; see the note
  at the top of this document.
- [`docs/embedding.md`](../embedding.md) — the protocol reference and extension
  guide.
