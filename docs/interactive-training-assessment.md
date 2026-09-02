# Interactive training assessment

This document describes Epistola Suite's generic assessment and embedding
infrastructure. Suite owns trusted workspace behavior: authentication,
authorization, navigation resolution, resource evaluation, and mutation/event
signals. It deliberately does **not** own courses, lessons, task IDs, course
revisions, or learner progress. Those belong to the embedding host.

The Website-side guide lives in the Website repository as
`docs/interactive-training.md`.

## Purpose and boundary

An embedded host can ask Suite whether a closed set of typed resource predicates
is true for the signed-in user. Suite evaluates them from its own domain queries
and UI permission model, then returns one status per submitted predicate. The
host derives task completion from those statuses and its own local event/manual
proofs.

This boundary is intentional:

- Suite remains reusable by any host and never needs course-specific code.
- A host cannot turn an unchecked browser checkbox into a claim about Suite
  state.
- Existing UI permissions remain the single authority; the bridge does not
  bypass them through the public REST API.
- The host receives only the minimum verdicts needed to render training, not
  template documents or other assessment payloads.

## Enabling embedding

Embedding is disabled unless `epistola.embedding.enabled` is true and the
parent origin is listed in `epistola.embedding.allowed-parent-origins`. The
local profile permits the local Website origin; deployment configuration must
name each real host origin explicitly.

The bridge is rendered by embedding-enabled Suite shell pages and the
full-page template editor, whether or not they are actually framed. Both render
the inert `#epistola-embed-config` JSON
island that bootstraps `static/js/embed-bridge.js`. The editor has its own Vite
mount and is not rendered inside the normal shell. Supplying the config there
is essential: without it, an editor save cannot notify the host until the user
leaves the editor.

## Security model

The bridge treats the parent as an untrusted caller until every boundary check
passes:

- outgoing messages use the configured parent origin, never `*`;
- incoming messages must originate from that allowlisted origin and have
  `event.source === window.parent`;
- resource references are typed identities (`resourceType`, tenant, catalog,
  and key), not host-provided URLs;
- `navigate` accepts only known views: `templates`, `detail`, `data-contract`,
  and `editor`;
- assessment uses the existing authenticated UI route and its normal domain
  permissions, never the public REST API;
- the bridge forwards Suite's `X-XSRF-TOKEN` on its same-origin POST, so the
  existing CSRF policy remains in force.

The CSRF header matters even though the host communicates with `postMessage`.
`postMessage` only reaches the iframe bridge; the bridge then performs an
ordinary same-origin UI request in the learner's Suite session. Do not loosen
CSRF protection or change the identity provider to address a `403`: ensure the
bridge forwards the current token instead.

## Protocol

All messages use `source` to distinguish the two applications. The canonical
shapes are local contracts in both repositories and are covered by fixture and
bridge tests.

### Session handshake

On every embedded load, Suite sends:

```json
{ "source": "epistola-suite", "type": "ready", "sessionStatus": "satisfied" }
```

The current implementation emits only `satisfied`. The login page does not
render the bridge, so it cannot emit `unauthenticated`; a host therefore cannot
yet distinguish that sign-in state from a missing bridge, timeout, or network
failure. `unauthenticated`, `forbidden`, and `unknown` remain assessment-result
statuses, and may be accepted as future handshake values, but are not current
handshake behavior.

### Assessment

The host submits a closed assessment request:

```json
{
  "source": "epistola-host",
  "type": "assess",
  "requestId": "opaque-host-request-id",
  "resources": [
    {
      "id": "template-under-test",
      "resourceType": "template",
      "tenantId": "demo",
      "catalogKey": "default",
      "key": "confirmation"
    }
  ],
  "predicates": [{ "type": "resource-exists", "resource": "template-under-test" }]
}
```

The bridge validates the request, POSTs its resources and predicates to
`POST /tenants/{tenantId}/training/assessment`, and returns the host request
ID unchanged:

```json
{
  "source": "epistola-suite",
  "type": "assessment-result",
  "requestId": "opaque-host-request-id",
  "results": [
    {
      "predicate": { "type": "resource-exists", "resource": "template-under-test" },
      "status": "satisfied"
    }
  ]
}
```

`results` is an **array**, with one item for every submitted predicate. This is
a protocol invariant. A host should reject an object-shaped or partial result
rather than guessing. The result statuses are `satisfied`, `unsatisfied`,
`unauthenticated`, `forbidden`, and `unknown`; only the host decides how they
appear in a lesson.

The route is registered in `DocumentTemplateRoutes` and handled by
`TemplateInspectionHandler`. It contains no host, course, lesson, or task
identifiers. `unauthenticated` and `forbidden` represent the current UI session
and permission outcome; `unknown` is a safe non-verdict for invalid or
unresolvable input.

## Current template predicates

Predicate evaluators are centralized in `TemplateInspectionHandler`, not in the
bridge. This keeps JavaScript as a transport/security layer and keeps resource
semantics near the Suite domain queries.

| Predicate                            | `satisfied` only when                                                                                                                                         |
| ------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `resource-exists`                    | The exact typed template identity resolves for the current tenant and user.                                                                                   |
| `data-contract-property`             | The exact property path exists in the saved Data Contract and its parent object lists it in `required`. Nested object paths are traversed.                    |
| `default-variant-heading-expression` | The default variant's persisted editor document contains the exact expression path in a rich-text heading. An expression in ordinary body text is not enough. |

The strict path and heading matching are deliberate. Assessment should verify
the learning objective, not merely find a similarly named field or expression
somewhere in a document. Add new evaluators here, with unit coverage for
positive and negative document-graph cases, rather than teaching the host to
inspect Suite data.

## Mutation and trusted interaction signals

After every covered catalog-resource mutation or explicit editor save the
bridge emits:

```json
{ "source": "epistola-suite", "type": "resource-mutated" }
```

The signal includes typed resource and operation metadata for diagnostics, not
an instruction to complete a particular task. The host reassesses its whole
current lesson, which handles one mutation affecting several predicates and
prevents brittle URL-based inference.

HTMX success is covered generically. Some modules save through raw `fetch`, so
their successful save paths call `notifyResourceMutated()` explicitly. This
includes the Data Contract editor, theme editor, and template editor. When
adding a UI mutation path, verify that it is covered by the generic HTMX hook
or make the explicit call after a successful save.

Suite also emits trusted closed interaction events when the learner reaches a
supported workspace action. Current examples are:

```json
{ "source": "epistola-suite", "type": "event", "event": "data-contract-opened" }
```

```json
{ "source": "epistola-suite", "type": "event", "event": "templates-opened" }
```

Events are evidence of interaction, not resource validity. The host may retain
them locally but should invalidate dependent event proof when an authoritative
prerequisite later becomes `unsatisfied`.

## Navigation

A host requests navigation with a closed view and, where needed, a typed
resource reference. Suite resolves all internal route details itself. In
particular, navigating to `editor` resolves the template's default variant;
the host never supplies an editor URL or a variant identifier.

For a resource-less `{ view: "templates" }` target, Suite derives the tenant
from the current authorized iframe path. A resource-bearing target is still
validated before Suite uses its tenant. The host cannot provide a standalone
tenant or URL.

Do not add a generic URL navigation escape hatch. It would turn an assessment
bridge into a cross-origin routing proxy and weaken the type/permission
boundary that makes embedding safe.

## Extending the feature

When adding a resource type, predicate, view, or mutation source:

1. Keep the request type closed and validate it in the bridge before any route
   or query is used.
2. Add an authoritative evaluator that uses existing authenticated UI/domain
   queries and returns a defined status for every input.
3. Add focused positive and negative tests for the resource graph or editor
   document shape being assessed.
4. Extend bridge tests for allowed-origin rejection, parent-source rejection,
   request validation, response shape, and any new closed navigation view.
5. Ensure all successful mutation paths notify the bridge, including raw-fetch
   Vite modules and full-page mounts.
6. Update this document and the host's local protocol mirror/fixtures in the
   same change.

Do not add course names, task IDs, learner completion records, or browser
storage to Suite. Those are host concerns. Likewise, do not expose generic
template inspection through the public REST API merely to serve training.

## Development and verification

Run Suite with the local profile and the editor watcher when testing embedded
editor behavior:

```bash
./gradlew :apps:epistola:bootRun --args='--spring.profiles.active=local'
pnpm --filter @epistola/editor watch
```

The focused bridge regression suite is `EmbeddingBridgeUiTest`; it exercises
the satisfied handshake, closed navigation, assessment results, mutation
signals, and the template state sequence across reloads. It does not exercise a
real cross-origin frame, the login-page handshake, HTTP embedding headers, or
permission outcomes. Predicate unit tests
cover nested Data Contract properties, required versus optional fields,
non-heading expressions, and default-variant selection.

For a documentation-only change, run `pnpm format:check` and `git diff --check`.
For changes to bridge, handler, templates, or evaluators, run the relevant
unit/UI tests in addition to the repository's normal Gradle verification.
