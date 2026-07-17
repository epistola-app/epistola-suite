# ADR 0012: Operation-level compatibility verdicts from a computed breaking-change log

- **Status:** Accepted
- **Date:** 2026-07-17
- **Deciders:** Epistola team
- **Tags:** compatibility, versioning, contract, api, architecture
- **Extends:** [ADR 0011](0011-version-compatibility-declared-and-verified.md)

## Context

ADR 0011 established the declared-and-verified compatibility system: the
contract carries a hand-maintained floor (`x-min-compatible-version`), the
suite derives and surfaces the accepted range `[floor .. apiVersion]`, clients
declare the contract version they target, and one rule
(`floor <= target <= apiVersion`) turns declarations into verdicts. Two
weaknesses were named in its design doc and both materialized immediately:

1. **Granularity.** The floor is a whole-contract statement, but breaking
   changes touch specific operations. The very first real verdict was a
   false negative: `valtimo-epistola-plugin` targets contract `0.8.0`, the
   floor sits at `0.10.0`, so the range rule says incompatible — yet the one
   breaking change in that window (0.10.0's pagination restructuring) touched
   exactly four list endpoints (`listTenants`, `listConsumers`,
   `listGenerationJobs`, `listDocuments`) that the plugin never calls. The
   plugin works in production; the matrix's debut row said it doesn't.
2. **The floor was guarded by nothing.** It is the single hand-maintained
   value in the system; a forgotten bump on a breaking release would make
   every downstream verdict silently report a false "compatible" — the worst
   failure mode, at the anchor.

Both weaknesses share a root: the system treated "did this release break
compatibility, and for whom?" as a scalar human declaration, when it is in
fact **computable from the spec history**. The contract repo is spec-first and
already runs `oasdiff` in CI; breaking-ness per operation is derivable
mechanically for every pair of released specs.

## Decision

Compatibility verdicts are computed from **spec history plus per-client usage
surfaces**, with the range rule retained as the fallback.

1. **The anchor publishes a computed compatibility log.**
   `epistola-contract` commits a deterministic `compatibility-log.json` with
   one entry per release: `{version, breaking, brokenOperations[]}`, generated
   by bundling and `oasdiff`-comparing every consecutive pair of release tags
   (`make compatibility-log`). Releases whose old spec layout cannot be
   compared are recorded with `computed: false`, never guessed.

2. **Clients declare their usage surface.** A client's `compatibility.json`
   additionally lists `operations` — every contract operation it calls, by
   `operationId`. In `valtimo-epistola-plugin` the list is maintained next to
   the generation task and verified against the source in CI: generated-client
   calls are scanned both ways (an undeclared call fails the build, and so
   does a declared-but-unused operation); the few hand-built HTTP calls are
   presence-checked by source markers.

3. **The aggregate joins at the operation level.** A client is compatible
   with a suite unless a breaking change in the window
   `(target .. suiteContract]` touches an operation the client uses. The
   reason names the release(s) and operation(s). The join runs only when it is
   sound: the feed declares operations, the log is reachable and well-formed,
   the log spans the whole window, and no entry in the window is
   `computed: false`. **Anything less falls back to the range rule** — an
   incomplete log must never produce a false green. Every row records which
   rule judged it (`basis: "operations" | "range"`).

4. **The floor stays, and is now enforced.** `x-min-compatible-version`
   remains the wire-level declaration behind the runtime `/ping` range (ADR
   0011 D4/D5 unchanged) and the fallback rule. Its staircase invariant is
   CI-gated in the contract repo (`make check-floor`): a breaking change vs
   the last release requires `info.version` bumped and the floor raised to
   exactly that version; no break requires the floor unchanged; and the log
   must cover the newest release. Breaking changes themselves are never
   blocked — only an inconsistent spec is.

5. **The matrix's home is the contract repo (resolves ADR 0011's deferred
   D6).** The anchor is the neutral ground every artifact already depends on
   and where the log lives, so judging runs there:
   `epistola-contract/compatibility/` fetches every artifact's feed (the suite
   publishes its own `compatibility.json` server feed, drift-guarded by a
   PR-only `verify-feed` job; the plugin its client feed), joins them with the
   log, and a seconds-cheap scheduled workflow commits the deterministic
   `aggregate.json` + `MATRIX.md` — the matrix gains memory and a stable URL.
   Only **verification** (the smoke, which boots published suite images) stays
   in `epistola-suite`, because evidence about suite releases belongs where
   suite releases happen; it runs per release, never on pull requests.

## Consequences

- The one human judgment ADR 0011 centralized ("is this change breaking?") is
  now _computed_ in the common case and _cross-checked_ where it remains
  declared. `oasdiff` becomes a trusted dependency; a semantic break under an
  unchanged shape is the case it cannot see, and the floor + changelog remain
  the human override path for that.
- Verdicts gain precision without losing safety: every code path that cannot
  prove operation-level soundness degrades to the stricter range rule, so the
  worst failure mode is a false red, not a false green.
- The plugin's row goes green truthfully instead of by fudging the floor down
  or force-upgrading the plugin's client on matrix grounds alone.
- The client-side `operations` list adds one maintenance point per client,
  drift-guarded by CI; the cost is a build failure with instructions, not a
  wrong matrix.
- The log regeneration after each contract release is one command
  (`make compatibility-log`), and staleness is caught by `check-floor` on the
  next PR.
