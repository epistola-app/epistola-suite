# Iframe embedding + assessment bridge

Epistola's web UI can be embedded in an `<iframe>` on a trusted host page and
driven from there with `postMessage`. Suite provides the generic plumbing only:
it does not ship courses, lessons, task IDs, or learner progress. A host owns
that content and asks Suite to assess a closed set of resource predicates in
the signed-in user's workspace.

This is the practical protocol guide. See
[Interactive training assessment](interactive-training-assessment.md) for the
Suite-owned assessment boundary, evaluator semantics, and extension checklist,
and [ADR 0015](adr/0015-iframe-embedding-bridge.md) for the framing and cookie
security decision.

## Status and configuration

Embedding is off by default. It is enabled in the `demo` and `local` profiles
so the hosted and local training environments can embed Suite; a normal
self-hosted installation remains unframeable and retains its existing cookie
behavior.

```yaml
epistola:
  embedding:
    enabled: false # true in application-demo.yaml and application-local.yaml
    allowed-parent-origins: [] # e.g. [https://epistola.app] or [http://localhost:4321] for local dev
```

`EmbeddingProperties` applies this one flag consistently:

1. CSP `frame-ancestors` changes from `'none'` to the explicit allowlist, and
   `X-Frame-Options: DENY` is disabled. CSP supports an origin list; leaving
   both headers in place would make them contradict each other.
2. Session and CSRF cookies use `SameSite=None; Secure`. This permits a
   genuinely cross-site allowlisted host. Cross-origin alone is not enough to
   require the change: SameSite uses the top-level page's schemeful site, so
   `epistola.app`/`demo.epistola.app` and localhost ports are same-site. See the
   [cookie specification's site-for-cookies algorithm](https://datatracker.ietf.org/doc/html/draft-ietf-httpbis-rfc6265bis/#section-5.2.1).
3. Suite renders the bridge script and its inert configuration JSON island.
   They are absent when embedding is disabled.

The local profile allowlists `http://localhost:4321`. Add a production host
origin explicitly; never use a wildcard origin.

## Shared message rules

Every message contains a `source` discriminator. Suite sends to one explicit
allowlisted origin, never `"*"`. Before acting on any host message, the bridge
checks both its allowlisted `event.origin` and
`event.source === window.parent`.

Hosts supply typed resource identities, never URLs. Suite turns an identity
into one of its own fixed route shapes and then uses normal HTMX navigation, so
the destination's ordinary authorization and existence checks still apply.
There is no special trusted-host route that could bypass the UI permission
model.

The host should treat messages as protocol data, not proof by themselves:
correlate assessment responses by request ID, reject malformed result arrays,
and derive course completion on its own side.

## Session handshake

On every embedding-enabled shell or full-page editor load Suite sends a `ready`
message before the host attempts assessment:

```json
{ "source": "epistola-suite", "type": "ready", "sessionStatus": "satisfied" }
```

The current bridge emits only `satisfied`. The login page does not render the
bridge/config island, so it emits no `ready` message; an unauthenticated iframe
therefore remains indistinguishable from a missing bridge or timeout. Hosts may
accept future `unauthenticated`, `forbidden`, and `unknown` ready values, but
must not assume Suite currently emits them.

## Host to Suite: `assess`

The host submits all resources and predicates needed for its current lesson in
one request. All resources must belong to the same tenant, and every predicate
must reference one declared resource.

```json
{
  "source": "epistola-host",
  "type": "assess",
  "requestId": "lesson-attempt-42",
  "resources": [
    {
      "id": "training-template",
      "resourceType": "template",
      "tenantId": "demo",
      "catalogKey": "default",
      "key": "training-confirmation"
    }
  ],
  "predicates": [
    { "type": "resource-exists", "resource": "training-template" },
    {
      "type": "data-contract-property",
      "resource": "training-template",
      "path": "recipientName",
      "required": true
    }
  ]
}
```

The bridge validates the closed request and makes its own same-origin,
authenticated UI request:

```text
POST /tenants/{tenantId}/training/assessment
Content-Type: application/json
X-XSRF-TOKEN: <current Suite CSRF token>
```

The host never calls this endpoint directly. `postMessage` reaches the bridge;
the bridge uses the learner's Suite session and forwards the CSRF token. A 403
here normally means that header was not sent, not that Authentik or the public
REST API needs changing.

## Suite to host: `assessment-result`

Suite returns the host request ID unchanged and sends **one result per submitted
predicate**:

```json
{
  "source": "epistola-suite",
  "type": "assessment-result",
  "requestId": "lesson-attempt-42",
  "results": [
    {
      "predicate": { "type": "resource-exists", "resource": "training-template" },
      "status": "satisfied"
    },
    {
      "predicate": {
        "type": "data-contract-property",
        "resource": "training-template",
        "path": "recipientName",
        "required": true
      },
      "status": "unsatisfied"
    }
  ]
}
```

`results` is an array, not an object keyed by predicate fields. This is an
important wire invariant: a partial or object-shaped response must be rejected
by a host rather than interpreted as progress. The current Website host rejects
the object shape but does not yet reject a partial array; that is an unresolved
host correctness gap.

Statuses are `satisfied`, `unsatisfied`, `unauthenticated`, `forbidden`, and
`unknown`. Suite evaluates the current resource state using authenticated UI
queries and returns no template/document payload. The currently supported
template predicates are:

- `resource-exists`;
- `data-contract-property`, including nested paths and exact requiredness;
- `default-variant-heading-expression`, requiring the exact expression path in
  a rich-text heading of the default variant.

The evaluator and the endpoint have no course, lesson, or task identifiers.

## Host to Suite: `navigate`

Navigation accepts a closed workspace target. The host names a view and, for a
resource-specific view, a typed resource identity:

```json
{
  "source": "epistola-host",
  "type": "navigate",
  "target": {
    "view": "data-contract",
    "resource": {
      "resourceType": "template",
      "tenantId": "demo",
      "catalogKey": "default",
      "key": "training-confirmation"
    }
  }
}
```

The closed views are `templates`, `detail`, `data-contract`, and `editor`.
`editor` resolves Suite's default variant internally; the host never provides a
variant ID or editor URL. Unknown resource types, malformed identifiers, and
unsupported view/resource combinations are ignored.

In the current bridge, `templates` also obtains its tenant ID from
`target.resource`; omitting the resource produces no navigation. This differs
from the nominal resource-optional list-view shape and must be resolved before
resource-less hosts rely on it.

## Suite to host: navigation and events

Suite reports navigation so the host can observe, but not control, ordinary
learner navigation inside the iframe:

```json
{
  "source": "epistola-suite",
  "type": "navigated",
  "path": "/tenants/demo/templates/default/training-confirmation/data-contract",
  "resource": {
    "resourceType": "template",
    "tenantId": "demo",
    "catalogKey": "default",
    "key": "training-confirmation"
  }
}
```

`resource` is `null` for a list or other page that is not one resource detail.
Suite derives it from its own current route, including on initial load, HTMX
navigation, and browser back/forward.

Some closed routes also establish a trusted interaction event:

```json
{ "source": "epistola-suite", "type": "event", "event": "data-contract-opened" }
```

An event says the interaction occurred; it does not say the resource remains
valid. Hosts that persist event proof must still invalidate it when a required
state predicate becomes authoritatively `unsatisfied`.

## Suite to host: `resource-mutated`

After a covered successful catalog-resource create, update, delete, or explicit
editor save, Suite emits:

```json
{
  "source": "epistola-suite",
  "type": "resource-mutated",
  "resource": {
    "resourceType": "template",
    "tenantId": "demo",
    "catalogKey": "default",
    "key": "training-confirmation"
  },
  "operation": "update"
}
```

The resource and operation are diagnostic metadata, not a host instruction to
mark a particular task complete. A host reassesses the whole active lesson,
because one mutation can affect several predicates and the changed resource may
not be the only relevant one.

### Why most mutation coverage is generic

Template, theme, and stencil mutations follow a uniform route convention. A
single `htmx:afterRequest` listener classifies successful operations without
per-handler Kotlin calls:

1. `PATCH /tenants/{tenant}/{type}/{catalogKey}/{key}` is an update.
2. `POST /tenants/{tenant}/{type}/{catalogKey}/{key}/delete` is a delete.
3. `POST /tenants/{tenant}/{type}` is a create; the bridge reads the existing
   `HX-Location` or `HX-Redirect` target to identify the created resource.

This avoids coupling every future mutation handler to embedding. Two paths need
explicit treatment:

- A raw `fetch()` bypasses HTMX, so a successful Data Contract, theme-editor,
  or template-editor save calls
  `window.epistolaEmbedBridge?.notifyResourceMutated(...)` directly.
- A browser-followed delete redirect has no observable XHR response. The
  handler adds a one-shot `resourceDeleted` query parameter, which the bridge
  consumes, emits once, and removes with `history.replaceState` so refreshes do
  not repeat it.

The template editor is a full-page Vite mount rather than a shell tab. It must
receive the same embedding config and bridge as the shell; otherwise raw editor
saves cannot notify an embedding host until the learner navigates away.

## Non-goals

- No training content or learner progress is stored in Suite.
- No REST API or MCP capability is added for training assessment.
- No per-tenant feature toggle is used: embedding is install-wide boot-time
  configuration.
- No generic URL-navigation escape hatch is available to the host.
