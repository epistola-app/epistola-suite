# ADR 0010: Strict CSP — removing `'unsafe-inline'` from `script-src`

- **Status:** Accepted — Option A
- **Date:** 2026-07-03
- **Deciders:** Epistola team
- **Tags:** security, csp, xss, frontend, htmx, architecture

## Context

The UI ships a strict-looking Content-Security-Policy (`SecurityConfig.kt`), but its
`script-src` contains `'unsafe-inline'`:

```
script-src 'self' 'unsafe-inline';
style-src 'self' 'unsafe-inline' https://fonts.googleapis.com;
```

`'unsafe-inline'` on `script-src` means any inline `<script>` block and any inline
`on*=` handler executes — **including ones an attacker injects**. In practice this
negates the XSS protection a CSP exists to provide. This is not theoretical for us:
issue **#644** is a concrete stored-XSS finding (asset picker renders uploaded
filenames via `innerHTML`), and today the CSP does nothing to contain it or the next
bug of its class. For a multi-tenant product that renders tenant-supplied content
(template names, filenames, data payloads, feedback text) server-side, injection bugs
_will_ recur; the question is whether the platform contains them.

What `'unsafe-inline'` currently holds up (surveyed 2026-07-03):

- **~36 templates** with inline `<script>` blocks — many introduced by our own
  "use `addEventListener` instead of `hx-on::`" convention (the CSP-motivated rule in
  `CLAUDE.md` pushed logic _into_ inline script tags, which the CSP then needs
  `'unsafe-inline'` to run — a circular fix).
- **~33 templates** with inline `on*=` attribute handlers (`onclick=`, `onchange=`, …).
- **309 inline `style=` attributes** across 53 files (relevant to `style-src` only).

The escape hatch browsers offer is the **nonce**: the server generates a random value
per response, puts it in the CSP header (`script-src 'self' 'nonce-R4nd0m'`), and
stamps it on every legitimate `<script>` tag. Injected markup cannot know the nonce,
so injected script does not run. Nonces do **not** apply to `on*=` attribute handlers
or `style=` attributes — those have no nonce-able carrier.

### The HTMX complication (why this is an architecture decision, not a config change)

HTMX swaps fragments into the page after load. A `<script>` inside a swapped fragment
must carry the **current page's** nonce to execute. HTMX offers
`htmx.config.inlineScriptNonce`, which auto-stamps the page nonce onto every script in
swapped-in content — but that stamps _attacker-injected_ scripts in swapped content
too, largely re-opening the hole the nonce closed. So a meaningful nonce CSP forces a
choice about where fragment behaviour lives — which is what makes this an ADR rather
than a hardening ticket.

The shell compounds this: `layout/shell.html` sets `<body hx-boost="true">`, so
**every internal navigation is itself an HTMX body-swap**, not a page load. The
browser never re-reads the CSP header on a swap — the nonce from the original hard
load stays in force for the page's whole lifetime, while each boosted-in page was
rendered with a _fresh_ nonce. A nonced inline script in a page **body** therefore
runs on hard load but is silently dead after boosted navigation. Under global boost
there is no reliable place for executable inline scripts in bodies at all; only the
shell `<head>` (which boost never re-swaps) can carry one.

## Decision drivers

- **Contain the recurring bug class.** Escaping bugs in server-rendered,
  tenant-supplied content are a _when_, not an _if_ (#644 is the existing example).
  A real CSP turns "stored XSS → account takeover" into "stored XSS → broken markup".
- **Pre-GA is the cheap moment.** After 1.0.0-GA, every inline script that works in
  the field is a regression risk; tightening the policy later breaks running
  deployments in ways only visible in the browser console. The one-time migration cost
  only grows.
- **Enterprise/security posture.** `script-src` with `'unsafe-inline'` is a standard
  pentest/questionnaire finding. Shipping GA with it means explaining it away for the
  product's lifetime; shipping without it is a durable claim.
- **Failure mode is silent.** A missed nonce doesn't error server-side — the script
  just doesn't run. Whatever we choose must come with build-time and browser-level
  enforcement, or it will rot.
- **Developer ergonomics.** Feature modules (`epistola-support-*`) ship UI too; any
  convention must be simple enough to follow across modules and enforceable by a test.
- **Proportionality for styles.** Style injection is a far weaker attack primitive
  than script injection, nonces don't apply to `style=` attributes at all, and we have
  309 of them.

## Considered options

### Option A — Nonce-based `script-src`; no executable scripts in bodies; `style-src` keeps `'unsafe-inline'`

Per-request nonce (filter + per-request CSP header). Because of global `hx-boost`
(see Context), the rule is uniform rather than split by page-vs-fragment:

- **No executable `<script>` anywhere in a `<body>`** — neither in fragments nor in
  full pages, since boosted navigation delivers full pages as body-swaps under the
  original load's nonce. Behaviour lives in static `/js/` files using **event
  delegation** — listeners installed once (on `document`, or via `htmx:load`/other
  `htmx:*` lifecycle events, which bubble), keyed on IDs/`data-*` attributes, so they
  work regardless of when and how the markup arrives.
  `htmx.config.inlineScriptNonce` is **not** used.
- **Server data passes via JSON data islands**: `<script type="application/json">`
  blocks (e.g. the editor bootstrap's `th:inline` payload). CSP governs only
  _executable_ scripts, so inert data blocks survive both hard loads and boosted
  swaps; the static bundle parses them on `htmx:load` and initialises components.
- **The shell `<head>` is the one executable-inline location** (boost never re-swaps
  it): at most a single nonce-stamped bootstrap block, or preferably fully external
  files, leaving the nonce as pure defence with no legitimate user.

`style-src` keeps `'unsafe-inline'` for now (htmx also injects an inline indicator
`<style>` that relies on it); Google Fonts is self-hosted so the
`fonts.googleapis.com`/`fonts.gstatic.com` origins leave the policy.

**Pros:** closes the script-injection hole for real (injected `<script>` and `on*=`
never execute, whether in the initial page or a swap); one uniform rule instead of a
page/fragment distinction developers must judge per template; forces behaviour into
static, cacheable, individually testable JS — the more HTMX-idiomatic shape anyway;
delegated listeners bind once instead of re-executing per swap; proportional on
styles.
**Cons:** the big sweep (~60 templates + new static JS); behaviour moves away from the
markup it animates (indirection); a new convention every UI-shipping module must
follow; per-request header rules out naive full-page HTML caching later (not used
today).

### Option B — Nonce-based `script-src` with `htmx.config.inlineScriptNonce`

Same nonce infrastructure, but fragments keep their inline scripts and HTMX re-stamps
the page nonce onto scripts in swapped content.

**Pros:** far smaller migration (only `on*=` handlers must move; inline `<script>`
blocks stay where they are).
**Cons:** any injected `<script>` **inside swapped content** gets the nonce too — and
HTMX swaps are exactly where tenant-supplied content lands. Protects the initial page
load, largely re-opens the hole for the dynamic half of the app. A CSP that looks
strict but isn't is worse than an honest `'unsafe-inline'` for auditability.

### Option C — Hash-based CSP (`'sha256-…'` allowlist)

Compute hashes of every legitimate inline script at build time; list them in the
header. No nonce infrastructure, and hashes even work for swapped fragments (a hash
is not per-request).

**Pros:** no per-request header machinery; static header stays cacheable.
**Cons:** every Thymeleaf-rendered dynamic value inside a script changes the hash, so
scripts with any server-rendered data (most of ours, e.g. `templates/editor.html`'s
`th:inline` blocks) can't be hashed without freezing their content; the hash list is
a hand-maintained build artifact that breaks on every script edit. Poor fit for an
SSR templating app.

### Option D — Status quo (keep `'unsafe-inline'`)

Accept the residual risk; rely on output-escaping discipline, the existing `hx-on::`
ban, and code review.

**Pros:** zero cost now; no new conventions.
**Cons:** the CSP provides ~no script-injection protection for the product's lifetime;
#644-class bugs remain fully exploitable; the finding resurfaces in every security
review; the migration only gets more expensive after GA.

### Option E — Full strictness including `style-src` (noted, out of scope)

Also remove `'unsafe-inline'` from `style-src`: migrate all 309 `style=` attributes
to classes/CSS custom properties (nonces don't apply to attributes). Roughly doubles
the sweep for a much weaker attack primitive (style injection ≈ defacement/limited
exfiltration, not code execution). Recorded to bound this ADR: a candidate follow-up,
not a GA precondition.

### Why not drop `hx-boost` instead?

Dropping boost would restore real page loads (fresh CSP header + nonce per
navigation), letting _page-level_ inline scripts keep working. Rejected because it
buys back very little: most of our inline scripts sit inside `th:fragment` sections
that HTMX returns as fragments anyway, where scripts stay broken under any nonce CSP,
and all `on*=` handlers must migrate regardless. It would also bake
boost-incompatibility into every template that kept an inline script — re-enabling
boost would mean redoing the sweep. The dependency only runs one way: with the
no-body-scripts rule, boost becomes a pure UX toggle that can be flipped freely
later; without it, the door closes. Boost itself is worth keeping for an
admin-style suite with constant navigation (no full re-parse/re-render between
pages), but it is explicitly _not_ load-bearing for this decision.

## Decision

**Accepted: Option A** — strict `script-src`, no executable scripts in bodies
(event-delegation architecture, JSON data islands), `style-src` keeps
`'unsafe-inline'` with self-hosted fonts, all locked in by build-time and
browser-level enforcement.

One refinement from implementation: since the migration leaves **zero legitimate
inline scripts** (the shell-head bootstrap moves to external files too), the final
policy is plain **`script-src 'self'`** — the per-request nonce machinery has no
remaining user and is not built. If a genuine need for an inline head script ever
appears, the nonce infrastructure described in Option A is the sanctioned way to
admit it; `'unsafe-inline'` never returns.

Option B is rejected because it protects exactly the half of the app where injection
is least likely to occur. Option C is rejected as structurally incompatible with
server-rendered dynamic scripts. Option D is rejected because it makes #644-class
bugs a permanent, unmitigated class. Option E is deliberately deferred.

## Gains

- **Injected script stops executing.** Stored/reflected XSS in any server-rendered or
  swapped content — the #644 class — is downgraded from code execution to inert
  markup. This is the single largest security-posture improvement available to the UI,
  and it protects against _future_ escaping bugs, not just known ones.
- **`on*=` handlers die as a class.** Nonce CSP blocks attribute handlers outright,
  which also retires the sprawl the `hx-on::` ban only partially addressed.
- **A GA-grade security claim.** "CSP without `unsafe-inline` script-src, no
  third-party origins" survives pentests and enterprise security questionnaires
  instead of generating findings.
- **No third-party origins at all.** Self-hosting the Inter font removes
  `fonts.googleapis.com`/`gstatic.com` — less supply-chain surface and no Google
  Fonts GDPR exposure (per the German court line of cases) for on-prem EU customers.
- **Better frontend shape as a side effect.** Fragment behaviour consolidates into
  static, cacheable, lintable, unit-testable JS files with a uniform delegation
  pattern, instead of ~36 scattered inline blocks that re-bind on every swap. The
  editor pages' `th:inline` payloads become inert `type="application/json"` islands —
  data stays inline, behaviour doesn't.
- **Regression-proof by construction.** An architecture test (extending the
  `HxOnUsageTest` pattern) fails the build on any `on*=` attribute or non-nonced
  `<script>` in any module's templates, and the Playwright base asserts zero CSP
  violations in the browser console — turning the silent failure mode into a red test.

## Consequences

- **The sweep:** ~36 templates lose inline scripts, ~33 lose `on*=` handlers (~60
  files across `apps/epistola` and the UI-shipping support modules), plus new static
  JS files under `static/js/`. Migration lands area-by-area behind a transition state
  (nonce infra added while `'unsafe-inline'` is still present), so the branch is
  shippable at every commit; the flip that removes `'unsafe-inline'` is one final
  commit.
- **A new hard convention:** no executable `<script>` in any `<body>`; no `on*=`
  attributes anywhere; server data passes via `type="application/json"` islands.
  Every UI-shipping feature module inherits it. `CLAUDE.md`/`docs/htmx.md` must be
  rewritten (the current guidance actively recommends inline `addEventListener`
  scripts in fragments), and the architecture test is the enforcement, not the docs.
- **Indirection cost:** behaviour lives in a JS file away from the markup it animates;
  a developer adding a fragment interaction writes a delegated handler keyed on a
  `data-*` hook instead of dropping a script next to the element. This is a real
  ergonomic regression for quick hacks, and the accepted price.
- **Per-request response variance:** the CSP header (and script tags) differ per
  request. Irrelevant today (SSR, no page caching), but full-page HTML caching/CDN
  would require revisiting (e.g. hash-based static shell or edge nonce injection).
- **Third-party embeds must comply:** the Scalar API-reference page and the Lit editor
  already load as `'self'`-hosted external scripts; their inline bootstraps move to
  static modules reading JSON islands. Anything future that _requires_ inline script
  injection or `eval` is simply not adoptable (eval is already blocked).
- **Residual risk accepted on styles:** `style-src 'unsafe-inline'` stays; style
  injection remains possible where markup injection exists. Documented as deliberate
  (Option E) rather than an oversight.
- **Observability gap remains open:** in production, a blocked script is still only a
  console error. A `report-to`/CSP-reporting endpoint is a possible follow-up, not
  part of this change.

## Related

- Issue #670 — the hardening issue this ADR decides; #644 — the stored-XSS finding
  motivating it.
- PR #672 — precursor slice: CDN removed from `script-src`, last `hx-on::` removed,
  `HxOnUsageTest` guard added.
- [`docs/htmx.md`](../htmx.md) and the CSP section of `CLAUDE.md` — conventions this
  ADR supersedes for fragment scripts.
- ADR 0004 (RFC 7807) — the same "decide the contract before GA freezes it" rationale.
