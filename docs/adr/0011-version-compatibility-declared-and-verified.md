# ADR 0011: Version compatibility as a declared, contract-anchored, empirically-verified property

- **Status:** Accepted
- **Date:** 2026-07-11
- **Deciders:** Epistola team
- **Tags:** compatibility, versioning, contract, api, architecture

## Context

Issue **#246** asks for a compatibility overview across our co-released
artifacts — `epistola-suite`, `epistola-contract`, `valtimo-epistola-plugin`,
and the Helm charts — "kept automatically". They all communicate via
`epistola-contract` (semver), which is the natural compatibility **anchor**.

We first built this as an **empirical** check: boot a published suite image and
observe which contract version it serves (`compatibility/` — the smoke harness,
`README.md`). Building it against real images surfaced a more fundamental fact:

**The suite has no compatibility contract to observe.** Verified against real
published images this cycle:

- It **cannot reliably report the contract version it implements** — `/api/ping`
  returns `apiVersion: "unknown"`, because the `server-kotlin-springboot4` JAR
  ships without an `Implementation-Version` manifest entry. We only recovered the
  version by reading the JAR _filename_ out of the image.
- It **declares no supported client range** anywhere.
- It **does not negotiate or reject** on the client's declared contract version.
  `ClientIdentityFilter` only checks that `User-Agent: epistola-contract/<v>` is
  present and prefixed (and only on `/generation/collect`); the version value is
  never compared to anything. Any `epistola-contract/<anything>` is accepted.
- There is exactly **one API version** (`application/vnd.epistola.v1+json`),
  enforced at the media-type layer but decoupled from the contract semver.

So compatibility today is **implicit and undeclared**. Two consequences follow.
First, an empirical matrix can only measure "does it boot and serve" — too
shallow to be the answer #246 wants, because **"kept automatically" implicitly
requires that compatibility be _expressed_; you cannot auto-maintain a fact that
nothing declares.** Second — and this is the opportunity — because _no_
compatibility mechanism exists yet, we are not retrofitting or reverse-
engineering; we get to **design the primitive** so the matrix falls out of it
cheaply. We are in the RC window, where deliberate, flagged breaking changes are
still allowed, so now is the right (and cheapest) time.

A read-only survey of `epistola-contract` made the path concrete: the contract
is spec-first (`epistola-api.yaml` `info.version` is the single source of
truth), **already** defines `POST /ping` as a _version-discovery_ endpoint whose
`PongDetailsDto` carries `serverVersion`/`apiVersion` and is described as
enabling "future compatibility features", and its published **client** already
exposes its own version at runtime via a generated resource — the exact mirror
the server lacks (the root cause of `"unknown"`). We are completing an existing
intent, not inventing one.

## Decision drivers

- **"Automatic" requires declaration.** You cannot maintain, verify, or render a
  compatibility fact that no artifact expresses. Declaration is the precondition.
- **Pre-1.0 is the cheap moment.** Adding a compatibility primitive (and any
  needed contract change) is a deliberate, flagged change now; after 1.0.0-GA the
  contract surface freezes and the same change becomes a breaking regression.
- **The external plugin must participate cheaply.** `valtimo-epistola-plugin` is
  a separate repo we do not control; any mechanism must let it join as a peer,
  not as a manually-reconciled special case.
- **Simplicity over machinery.** A clean canvas invites over-engineering (a full
  negotiation protocol where a version string would do). Prefer the smallest
  mechanism that is _true_.
- **Pre-1.0 semver is weak.** While the contract is `0.x`, semver does **not**
  guarantee minor-version backward compatibility, so a purely _computed_
  compatibility rule would be unsafe.
- **Cross-surface consistency.** Whatever the suite exposes must be consistent
  across REST, the web UI, and MCP.

## Considered options

### Option A — Declared, contract-anchored, empirically verified (chosen)

Make compatibility a **declared** property, anchored on the contract, and
**verify** the declarations empirically:

- The **contract** is the anchor: it becomes reliably self-identifying (fix
  `apiVersion: "unknown"`) and **owns the declaration format** (extend the
  existing `/ping` `PongDetailsDto` in the spec — one edit reaches the generated
  server interfaces, the generated client, docs, and mock).
- The **suite** _fills_ that declaration — the contract version it implements and
  the **explicitly declared** supported client range.
- The **plugin** declares its target contract version in the same shared format
  (manual row until it adopts it).
- The **empirical harness verifies** declarations rather than defining them
  (declaration proposes, CI verifies).
- **Declarations local, aggregation central:** each artifact publishes its own
  declaration; an aggregator only reads them, applies the semver rule, and
  renders. Aggregator location stays a reversible deployment detail (the feed
  _format_ is the interface).
- **Declaration over enforcement:** we declare compatibility; runtime
  negotiation/rejection is deliberately deferred.

The detailed shape is recorded as decisions **D1–D6** in
[`compatibility/DESIGN.md`](../../compatibility/DESIGN.md).

**Pros:** turns compatibility from a guess into a _read + a rule_; fixes a real
bug (`apiVersion: "unknown"`) as a byproduct; completes the contract's existing
`/ping` intent instead of inventing; the plugin joins as a peer; keeps the
empirical harness useful as the _verifier_; extends to enforcement later with no
rework; safe pre-1.0 because the range is declared, not assumed.
**Cons:** spans three repos and needs coordination; the matrix is _advisory_
(the suite still accepts any client) until/unless enforcement is added; the
declared range must be kept honest by a policy owner (with the harness as check).

### Option B — Pure empirical reverse-engineering

Leave the product as-is; build per-contract-version generated-client test packs
and run them against the suite to _discover_ compatibility behaviorally.

**Pros:** no product change; exercises real request/response behavior.
**Cons:** expensive (a client per version, auth, meaningful assertions); it can
only observe what the product exposes, and the product exposes nothing about
version compatibility — the server never rejects on version, so there is little
to observe beyond raw schema drift; and it largely re-derives what semver already
promises. High cost, low marginal signal.

### Option C — Runtime negotiation / enforcement (Kafka-style)

Add an `ApiVersions`-style handshake: the suite advertises supported versions and
**rejects** incompatible clients.

**Pros:** strongest guarantee; self-enforcing; decouples upgrade order.
**Cons:** a large build for a system that today serves a single API version and a
co-released core; premature. It is a natural _later_ layer **on top of**
declaration (Option A), not a substitute for it, and can be added without rework
if a real need appears.

### Option D — Declarative-only via computed semver + a hand-maintained table

The typical prior-art shape: a docs table, compatibility computed from a semver
rule, no verification.

**Pros:** cheapest to start.
**Cons:** drifts silently; **unsafe while the contract is `0.x`** (computed minor
compatibility is not guaranteed pre-1.0); no verification that the computed
verdict is true; the plugin row stays manual with nothing to check it against.

### Option E — Status quo (implicit, undeclared)

Accept that compatibility is undeclared; ship the boot-only smoke as the matrix.

**Pros:** zero further work.
**Cons:** the matrix can only ever say "it boots"; #246's "kept automatically" is
unsatisfiable because there is nothing to keep. Rejected.

## Decision

**Accepted: Option A.** Compatibility is a **declared, contract-anchored,
empirically-verified** property. The contract is made self-identifying and owns
the declaration format; the suite fills it; the plugin declares in the same
format; the empirical harness verifies; an aggregator reads declarations and
renders. Declaration is chosen over enforcement, and the concrete sub-decisions
are recorded as D1–D6 in [`compatibility/DESIGN.md`](../../compatibility/DESIGN.md).

Option B is rejected as high-cost/low-signal against a product that exposes
nothing to observe. Option C is deferred as a later layer on top of A, not a
substitute. Option D is rejected as unsafe pre-1.0 and unverified. Option E is
rejected because it makes #246 structurally unsatisfiable.

## Gains

- **Compatibility becomes a read + a rule, not a guess** — the precondition for
  "kept automatically".
- **The running app becomes self-describing** — fixing `apiVersion: "unknown"`,
  which is a standalone bug fix valuable on its own.
- **We complete the contract's existing intent** (`/ping` as version discovery)
  rather than inventing a parallel surface — one spec edit reaches every consumer.
- **The plugin participates as a peer**, ending its status as a manual exception.
- **The empirical harness keeps earning its keep** as the _verifier_ of
  declarations, and as a "does suite S boot and serve" regression check.
- **Enforcement stays open** — Option C can be layered on later with no rework.

## Consequences

- **Cross-repo and phased.** Execution order: (1) contract self-identifies
  [`epistola-contract`]; (2) extend `PongDetailsDto` [spec] + suite fills it
  [this repo]; (3) harness verifies [this repo]; (4) plugin declares
  [`valtimo-epistola-plugin`]; (5) aggregate + render. The contract and plugin
  changes are **coordinated with those repos, not made from `epistola-suite`.**
- **The matrix is advisory, not enforced.** Under declaration-over-enforcement
  the suite still accepts any client; the matrix informs, it does not gate. That
  is acceptable now and revisited only if Option C is adopted.
- **A declared range must be kept honest.** Someone owns the supported-min policy;
  the empirical harness is the mechanical check that the declaration holds.
- **Aggregator location is deferred and reversible.** The feed _format_ is the
  interface; whether the aggregator lives in `epistola-suite` or a neutral repo
  is a later, low-stakes call (see DESIGN.md R8 / D6).
- **Timing pressure.** The foundational contract change (self-identification) and
  the `PongDetailsDto` extension should land before 1.0.0-GA freezes the surface.

## Related

- Issue **#246** — the compatibility-matrix request this ADR answers.
- [`compatibility/DESIGN.md`](../../compatibility/DESIGN.md) — the detailed
  requirements (R1–R8), decisions (D1–D6), and execution plan.
- [`compatibility/README.md`](../../compatibility/README.md) — the empirical
  smoke harness (the verification layer).
- `epistola-contract` — `epistola-api.yaml`, `spec/paths/ping.yaml`,
  `spec/components/schemas/ping.yaml` (`PongDetailsDto`), and the client's
  version-resource pattern the server will mirror.
- ADR 0004 (RFC 7807) and ADR 0010 (strict CSP) — the same "decide the contract
  before GA freezes it" rationale.
