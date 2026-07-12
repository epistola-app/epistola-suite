# Compatibility matrix

Which versions of Epistola's co-released artifacts actually work together —
established by **running them and observing**, not by declaration.

> **Working ideology:** it doesn't have to be perfect the first time. It just
> needs to work, then get better with each commit. This directory is the v0
> foundation.

## Approach: verified, not declared

A cell in the matrix goes green because we **booted a real, published
`epistola-suite` image and watched it serve the API** — not because a semver
rule predicted it should. `epistola-contract` is the compatibility **anchor**
(the wire language the suite and external clients both speak), so a cell is the
pair **(suite version, contract version)**.

Because the harness runs against _published artifacts_ (a container image) and
never reaches into app source, it is self-contained and could later be lifted
into its own repo unchanged — for now it lives here, in `epistola-suite`.

## What a cell run does

`smoke.sh` runs a single cell:

1. starts a throwaway Postgres,
2. boots the given suite image with the `localauth` profile (embedded Flyway
   migrates on boot),
3. **reads the contract version from the image** — the
   `server-kotlin-springboot4-<version>.jar` in `BOOT-INF/lib` — which becomes
   the cell's authoritative `contract` (recorded as `contractSource: "image"`),
4. polls `POST /api/ping` **with client-identity headers**
   (`X-EP-Node-Id`, `User-Agent: epistola-contract/<version>`) until it reports
   `status: UP` — this doubles as the readiness signal,
5. then makes an **authenticated** `/api/ping` (the `.details` object is
   auth-gated) to read the server's declared range
   `[minCompatibleApiVersion .. apiVersion]` and check the cell's contract falls
   inside it — recorded as `declaredRange` + `rangeVerified`, or omitted when the
   image predates the field,
6. records the outcome into [`matrix.json`](./matrix.json).

Two things learned the hard way, now baked in:

- **Boot profile.** The suite fails fast unless an authentication mechanism is
  configured (`No authentication mechanism configured`), so we boot with
  `localauth` (form login, in-memory users). We only make an anonymous request,
  so the mechanism is never exercised — it just lets the app start. `prod` is
  avoided (needs encryption keys + a pre-migrated DB); override with `--profile`.
- **Why read the contract from the image, not `/api/ping`.** Historically the
  runtime `apiVersion` was `"unknown"` — the contract JAR carried no
  `Implementation-Version` manifest entry. That root cause is now fixed (D1: the
  contract self-identifies via a baked-in version resource that `/ping` reports),
  but the harness still reads the JAR **filename**: it boots _already-published_
  images that predate the fix and would still report `"unknown"`, and the
  filename is inspectable without even booting. Once the fix has shipped in the
  images under test, the harness can cross-check the declared `/ping` value
  against the filename (and eventually trust the declaration).
- **Why client-identity headers + ping-based readiness.** Older suites _require_
  `X-EP-Node-Id` on `/api/ping` (400 without it), and `/readyz` / `/livez` are
  not reliably `200` across versions (some redirect to login). A header-carrying
  ping returning `UP` is the version-robust "it's serving" signal, so we poll
  that instead of the actuator probes.

**Limits — on purpose:**

- The pass/fail gate is the **anonymous** reachability ping (identity headers, no
  API key) — it proves "this suite boots and serves the contract surface", not that
  individual endpoints behave correctly. The authenticated range read layered on
  top needs a valid API key (the demo key under a demo-capable profile); deeper
  endpoint exercise is still a later step.
- Only the **co-released pairing** is exercised (the contract the image bundles).
  Cross-version client skew — an older client against a newer suite, the real
  point of a matrix — comes once we vary the client's declared contract version.

## Run one cell locally

```bash
# against a published image (contract version is read from the image):
compatibility/smoke.sh --image ghcr.io/<owner>/epistola-suite:0.20.0 --suite 0.20.0

# --contract is only a fallback label used if image inspection fails:
compatibility/smoke.sh --image ghcr.io/<owner>/epistola-suite:latest \
  --suite latest --out compatibility/matrix.json
```

Requires `docker`, `curl`, `jq`. Exit code is `0` on a passing cell, non-zero
otherwise. The updated `matrix.json` is the single source of truth.

## Render the human-readable table

`matrix.json` is the source of truth; [`MATRIX.md`](./MATRIX.md) is a rendered
_view_ of it (per R6 — the table is never hand-edited). Regenerate it after a run:

```bash
compatibility/render.sh                 # matrix.json → MATRIX.md
compatibility/render.sh --out -         # print to stdout instead
```

Requires only `jq`. CI runs this after the smoke and posts the table to the job
summary.

## Aggregate external client feeds

The suite side gives per-cell _ranges_; external **clients** (e.g.
`valtimo-epistola-plugin`) publish their own machine-readable `compatibility.json`
declaring the contract version they target. `aggregate.sh` joins the two — applying
the rule `floor <= target <= apiVersion` — into plugin↔suite verdicts:

A feed source is a **local path or an `http(s)` URL** (each client publishes its
`compatibility.json` in its own repo, so remote is the normal case), and
[`feeds.txt`](./feeds.txt) lists the sources the matrix aggregates:

```bash
# from the committed feed list (URLs + local paths):
compatibility/aggregate.sh --feeds-file compatibility/feeds.txt \
  --out compatibility/aggregate.json
# or a one-off:
compatibility/aggregate.sh --feed https://raw.githubusercontent.com/…/compatibility.json
compatibility/render.sh                 # picks up aggregate.json → second table
```

`aggregate.sh` (jq + curl) writes rows with a `compatible` flag and a human reason;
`render.sh` adds a "Plugin ↔ suite compatibility" table when `aggregate.json` has
rows. Fetching is **best effort** — a feed whose repo has not merged its
declaration yet (404) is warned and skipped, so `feeds.txt` can list a source
before that side ships. **CI runs this** after the smoke (feeds from `feeds.txt`),
so the plugin table appears in the job summary automatically once the plugin's feed
is live. The declarations stay with each artifact; the aggregator only reads feeds
and applies the rule (R8). The only remaining D6 choice is where the aggregator
ultimately _lives_ (in this repo vs a neutral repo).

## Roadmap (each an independent, shippable step)

**Done:** the smoke harness; release/on-demand CI (`.github/workflows/compatibility.yml`);
cross-version robustness (client-identity headers, ping-based readiness);
authoritative contract version read from the image; **declared-range verification**
— an authenticated `/api/ping` reads the server's `[minCompatibleApiVersion ..
apiVersion]` and asserts the cell's contract falls inside it, recording
`declaredRange` + `rangeVerified` on the cell (degrades to reachability-only
against images that predate the field). **Exercised end-to-end** against a
locally built compat-aware image (`rangeVerified: true`) and the published
`:latest` image (graceful degradation); the in-process contract is covered by
`CollectEndpointSmokeIT`. Note the authenticated read needs a valid API key, which
under `--profile localauth,demo` seeds ~60-90s after boot — tune `RANGE_TIMEOUT`.
**Human-readable render** — `render.sh` turns `matrix.json` into
[`MATRIX.md`](./MATRIX.md); CI posts it to the job summary.
**Aggregate** — `aggregate.sh` fetches external client feeds ([`feeds.txt`](./feeds.txt),
URLs or paths) and joins them with the suite ranges into plugin↔suite verdicts,
rendered as a second table; **CI runs it** after the smoke. The full pipeline
(declare → verify → aggregate → render) runs automatically.

**Next:**

1. **Vary the client** — run the smoke against a range of contract versions
   (fixed suite image, varying the client's declared contract), turning one cell
   into a real row. The declared range makes each such cell a real
   compatible/incompatible verdict, not just reachability.
2. **Commit results back** from CI so `matrix.json` + `MATRIX.md` persist across
   runs; later, publish as a feed.
3. **Deeper checks** — go beyond `/api/ping` and use the seeded demo API key
   (`epk_demo_…`, available under a demo-capable profile) to exercise authenticated
   contract endpoints, not just the range declaration.
