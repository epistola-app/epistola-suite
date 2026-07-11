# Compatibility system — requirements & design criteria

Status: the design forks are **decided** (D1–D6 below); the accepted decision is
recorded in [ADR 0011](../docs/adr/0011-version-compatibility-declared-and-verified.md).
This doc is the detailed spec behind that ADR. Companion to
[`README.md`](./README.md) (the current empirical smoke harness) and issue #246.

> **Working ideology:** it doesn't have to be perfect the first time. It just
> needs to work, then get better with each commit.

## Why this doc exists (what we learned)

We set out to build an *empirical* compatibility matrix — boot a published suite
image and observe which contract versions it works with. Building it surfaced a
more fundamental fact:

**The suite has no compatibility contract to observe.** Concretely, verified
this session against real published images:

- It **cannot reliably report the contract version it implements** — `/api/ping`
  returns `apiVersion: "unknown"` because the contract JAR ships without an
  `Implementation-Version` manifest entry. We only recovered the version by
  reading the JAR *filename* out of the image.
- It **does not declare a supported client range**, anywhere.
- It **does not negotiate or reject** on the client's declared contract version.
  `ClientIdentityFilter` only checks `User-Agent: epistola-contract/<v>` is
  *present and prefixed* (and only on `/generation/collect`); the version value
  is never compared to anything. Any `epistola-contract/<anything>` is accepted.
- There is exactly **one API version** (`application/vnd.epistola.v1+json`,
  enforced at the media-type layer), decoupled from the contract semver. Nothing
  serves `v2`.

So compatibility today is **implicit and undeclared**. An empirical matrix can
therefore only measure "does it boot and serve" — too shallow to be the answer
#246 wants ("kept **automatically**" implicitly requires that compatibility be
*expressed*; you cannot auto-maintain a fact nothing declares).

**The opportunity:** because no compatibility mechanism exists yet, we are not
retrofitting or reverse-engineering — we get to *design the primitive* so the
matrix falls out of it cheaply. We're in the RC window, where deliberate,
flagged breaking changes are still allowed, so now is the right time.

## Per-artifact roles (they are NOT uniform)

Compatibility is anchored on **`epistola-contract`** (the wire language the suite
and the external `valtimo-epistola-plugin` both speak). Each artifact plays a
different role:

| Artifact | Role | What it must contribute |
| --- | --- | --- |
| **epistola-contract** | **Anchor** (not a consumer of itself) | (1) Be **self-identifying** — embed its version so consumers can report it at runtime (fixes `apiVersion: "unknown"`). (2) **Define the declaration format** once, since both suite and plugin depend on it. |
| **epistola-suite** | **Declarer** | Declare the contract version it implements **and** the client range it supports; expose both at runtime (endpoint) and at rest (manifest/feed). |
| **valtimo-epistola-plugin** | **Declarer (external)** | Declare the contract version it targets, in its own published metadata (separate repo, so it must be able to participate cheaply). |
| **Helm charts** | **Mapping** | Declare which suite version they deploy — already done via `appVersion` (informational, decoupled from chart version). |

The key correction to "every artifact declares the same thing": the **contract's**
contribution is to be readable and to own the *format*; the **consumers** declare
what contract they speak; the **charts** just map to a suite.

## Requirements (what the primitive must provide)

- **R1 — Reliable self-identification.** Any running suite (and ideally any built
  artifact) can truthfully state the contract version it implements, without
  cracking open JAR filenames. (Fixes `apiVersion: "unknown"`.)
- **R2 — Declared support range.** A suite can declare the range of client
  contract versions it supports (e.g. `implements 0.10.0, supports clients
  >= 0.4.0`), not just its own point version.
- **R3 — Readable two ways.** Declarations are readable **at runtime** (an
  endpoint, for live checks and the app's own UI) and **at rest** (a
  machine-readable manifest/feed, for CI and the external plugin to pull without
  booting anything).
- **R4 — One compatibility rule.** A single, documented rule turns declarations
  into a verdict (semver-based: same major, client minor ≤ server minor — or the
  explicitly declared range). Compatibility becomes a *computation over
  declarations*, not a guess.
- **R5 — External participation.** The plugin (a repo we don't control) can
  declare its target contract version cheaply, so the external side of the
  matrix is knowable rather than hand-maintained.
- **R6 — Matrix = aggregation + presentation.** The matrix/table is just a view
  over R1–R4 (read declarations, apply the rule, render), kept current by CI —
  not a bespoke reverse-engineering pipeline.
- **R7 — Cross-surface consistency.** Whatever compatibility info the suite
  exposes is consistent across REST, the web UI, and MCP (per the "all three
  surfaces" rule).
- **R8 — Declarations local, aggregation central.** Each artifact owns and
  publishes *its own* declaration where it lives (suite in the suite, plugin in
  the plugin repo, contract in its build). Any aggregator only *reads* those
  feeds, applies the rule, and renders — it never owns declaration logic. This
  keeps the feed **format** as the real interface and the aggregator's location a
  reversible deployment detail.

## Where the aggregator/matrix lives (declarations vs. aggregation)

This split (R8) is what finally settles the long-running "separate repo vs.
in-repo" question.

- Earlier, a **separate repo was a liability**: the version data lived inside
  `epistola-suite`, so a separate repo turned local reads into cross-repo
  plumbing.
- Under the self-declaration model that objection **disappears**: once every
  artifact publishes its own declaration, *no* artifact's data is local/privileged
  — they are all feeds. A neutral **aggregator** repo then becomes not just
  viable but arguably the cleanest home, because it treats the external
  `valtimo-epistola-plugin` as a **peer** instead of a manually-maintained
  exception. (This is the AAP-matrix / neutral-aggregator pattern.)

**But the sequencing and the split are load-bearing:**

1. **Declarations first.** The declaration primitives (R1–R5) must exist *in each
   artifact* before an aggregator is worth anything — an aggregator with no feeds
   to read is an empty shell. Build the feeds; the aggregator comes after.
2. **The separate repo is the aggregator, NOT the declarations.** Moving
   declaration logic into a central repo re-couples everything and loses the
   benefit. Declarations stay with each artifact; only aggregation centralises.
3. **Then the boundary is low-stakes and reversible.** Because the feed *format*
   is the interface, whether the aggregator sits in `epistola-suite` or its own
   repo is a deployment choice, not an architectural one. Its real payoff is
   **neutrality**, which matters more as the number of independently-owned
   artifacts grows.

So: we are **not** choosing the repo now. We are building the self-declaration
feeds that *earn* that choice and keep it reversible. (Resolves open question #6.)

## Design principles / constraints (the fixed walls)

- **Contract is the semver anchor** — not up for redesign.
- **Declaration over enforcement.** Start by *declaring* compatibility (cheap,
  maintainable). Runtime *negotiation/rejection* (Kafka-style handshake) is a
  bigger step, added only if a real need appears — not by default.
- **Simplest thing that's true.** Prefer a readable version string + optional
  range over elaborate protocol machinery. Avoid over-building the clean canvas.
- **External plugin must participate cheaply** — no mechanism that assumes we
  control or can rebuild the plugin.
- **RC window** — deliberate breaking changes are allowed now and must be flagged
  (`feat!:` / `BREAKING CHANGE:`); this window narrows at 1.0.0-GA.
- **Keep the harness useful.** The existing empirical smoke stays valuable as a
  "does suite S boot and serve" regression check; it becomes the *light
  verification* layer under R6, not the whole matrix.

## Explicitly out of scope (for now)

- Runtime negotiation / rejecting incompatible clients (enforcement) — deferred
  behind declaration (R2/R4). Revisit if a concrete need arises.
- Multiple simultaneous API versions (`v2` alongside `v1`) — not needed; one API
  version, contract evolves under it via semver.
- A full per-contract-version generated-client test pack — expensive, and largely
  redundant once R1–R4 make compatibility a declared, computable fact.

## What the contract repo already gives us (verified, read-only)

Explored `epistola-app/epistola-contract` (local `/home/whit3st/projects/contract`).
Encouraging: most of the primitive is already scaffolded.

- **Spec-first, single version source.** `epistola-api.yaml` `info.version`
  (currently `0.11.0`) is the source of truth; the `server-kotlin-springboot4`
  and client Gradle versions derive from it. "The contract's version" is
  unambiguous and already central.
- **The contract already anticipated compatibility.** `POST /ping` is defined in
  the spec as the version-discovery endpoint, and its `PongDetailsDto` already
  carries `serverVersion` + `apiVersion` ("the API spec version supported by this
  server"); the path is described as enabling "future compatibility features". We
  are *completing an existing intent*, not inventing one.
- **A client IS published** — `client-spring3-restclient` (Spring Boot 3 /
  Jackson 2). Per-version client testing is more feasible than first assumed
  (caveat: Boot 3 / Jackson 2 vs the suite's Boot 4 / Jackson 3).
- **Root cause of `apiVersion: "unknown"`.** The `server-kotlin-springboot4`
  build never customises the jar manifest, and Gradle omits `Implementation-
  Version` by default. The version *is* already inside the jar as the resource
  `/openapi/epistola-contract.yaml` (`info.version`) — just not exposed. The
  **client already solves this**: its build writes `epistola-contract-version.txt`
  and `ClientIdentity` reads it lazily; the server lacks that mirror.

### Concrete direction this gives R1 / R2

- **R1 (self-identify) is a small contract-repo change**, two idiomatic options:
  - (a) mirror the client's `generateContractVersionResource` in the server build
    → `epistola-contract-version.txt`, and have the suite read that classpath
    resource (reliable in Spring Boot fat jars; symmetric with the client);
  - (b) add `Implementation-Version` to the server jar manifest from
    `project.version` (the suite's `GetServerInfo` already reads the manifest →
    zero suite change, but nested-jar package-version resolution is less certain).
  - (a) is more robust and matches repo conventions. Either lands in
    `epistola-contract` (coordinate with that repo — not ours to edit directly).
- **R2/R4 (declared range) extend `PongDetailsDto` in the spec** — add e.g.
  `minClientVersion` / `supportedContractVersions` next to `serverVersion` /
  `apiVersion` in `spec/components/schemas/ping.yaml`. One spec edit flows to the
  generated server interfaces, the generated client model, the docs, and the mock
  server. This confirms the roles table: **the contract owns the format; the
  suite fills it.** (A spec-level `x-compatibility` vendor extension is also
  idiomatic — precedent: `x-problem-types` drives a generated constant.)

## Decisions

The design forks are settled (all confirmed):

- **D1 — Contract self-identifies via a resource file + accessor.** The
  `server-kotlin-springboot4` build writes `epistola-contract-version.txt` from
  the spec's `info.version` (mirroring the client's `generateContractVersionResource`)
  and exposes a small accessor; the suite calls it. Classpath resources are
  reliable in Spring Boot fat jars (manifest package-version — the cause of the
  `"unknown"` — is not). Keeps the "how to know my version" in the anchor. (R1)
- **D2 — Runtime endpoint first; at-rest feed later.** Extend the existing
  `/ping` → `PongDetailsDto` now (the harness already boots to read it). Build a
  static at-rest manifest/feed only when the aggregator or the plugin needs it —
  don't build ahead of need. (R2, R3)
- **D3 — The plugin declares in the shared (contract-defined) format; manual
  until then.** Since the plugin depends on the contract, it can emit the same
  declaration cheaply once the format exists (peer participation). Its matrix row
  is hand-maintained in the interim. Keying off `User-Agent` is a runtime
  *observation*, not a declaration, so it's rejected. (R5)
- **D4 — Declare the supported range explicitly, verify empirically.** The suite
  declares a supported *min* client version (policy); the empirical harness
  verifies it. A purely *computed* semver range is unsafe while the contract is
  pre-1.0 (`0.x`), where minor bumps may break. Computed semver can take over
  after 1.0.0-GA. (R2, R4 — declaration proposes, CI verifies.)
- **D5 — Format in the contract spec; suite fills it.** (from the roles table /
  contract findings — `PongDetailsDto` in `spec/components/schemas/ping.yaml`.)
- **D6 — Aggregator location deferred & reversible** (R8): a neutral repo becomes
  viable once feeds exist; the feed *format* is the interface. Concrete rendered
  target (e.g. `docs/compatibility-matrix.md`) still open.

## Remaining detail work (not blocking)

- Exact new `PongDetailsDto` field name(s) for the supported range (e.g.
  `minClientVersion` / `supportedContractVersions`) — decided when the spec is edited.
- Whether the suite reads the contract accessor directly or the raw resource (D1
  sub-detail).
- The at-rest feed's format/location (deferred with D2).
- Who sets the declared min and how it's kept honest (policy owner; the harness
  is the verifier).

## Execution plan (order)

The forks are decided; execution spans repos, so sequence matters. Changes in
`epistola-contract` and `valtimo-epistola-plugin` are **coordinated with those
repos, not edited from here.**

1. **R1 / D1 — contract self-identifies** (in `epistola-contract`): server build
   emits the version resource + accessor. Foundational; also a standalone fix for
   `apiVersion: "unknown"`. Then the suite reports its real contract version and
   the harness can stop reading JAR filenames.
2. **D5 / D2 — extend `PongDetailsDto`** (spec in `epistola-contract`) with the
   declared range; **suite fills it** (in this repo).
3. **Verify** the declared range with the empirical harness (this repo) — the
   existing smoke grows an assertion that the running suite's declaration holds.
4. **D3 — plugin declaration** (coordinate with `valtimo-epistola-plugin`); manual
   row until adopted.
5. **Aggregate + render** (D6) — read declarations, apply the rule, render the
   matrix; decide the aggregator's home when feeds exist.
