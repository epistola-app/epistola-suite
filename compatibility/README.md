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
5. records the outcome into [`matrix.json`](./matrix.json).

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

- The request is **anonymous** (identity headers, no API key), so it proves
  _reachability_ — "this suite boots and serves the contract surface" — not that
  individual endpoints behave correctly. Deeper endpoint exercise (using the
  seeded demo key) is a later step.
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
otherwise. The updated `matrix.json` is the single source of truth; a
human-readable table is rendered from it (later).

## Roadmap (each an independent, shippable step)

**Done:** the smoke harness; release/on-demand CI (`.github/workflows/compatibility.yml`);
cross-version robustness (client-identity headers, ping-based readiness);
authoritative contract version read from the image.

**Next:**

1. **Vary the client** — run the smoke against a range of contract versions
   (fixed suite image, varying the client's declared contract), turning one cell
   into a real row.
2. **Render** a human-readable table from `matrix.json`.
3. **Commit results back** from CI; later, publish as a feed.
4. **Deeper checks** — use the seeded demo API key
   (`epk_demo_…`, available under the `demo` profile) to exercise authenticated
   contract endpoints, not just reachability.
5. **Plugin (transitive)** — infer `valtimo-epistola-plugin` compatibility from
   its declared supported contract range against verified suite↔contract cells.
