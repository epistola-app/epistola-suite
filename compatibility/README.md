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

Because the harness runs against *published artifacts* (a container image) and
never reaches into app source, it is self-contained and could later be lifted
into its own repo unchanged — for now it lives here, in `epistola-suite`.

## What v0 does (and deliberately does NOT do yet)

`smoke.sh` runs a single cell:

1. starts a throwaway Postgres,
2. boots the given suite image (embedded Flyway migrates on boot),
3. waits for readiness (`/readyz` on the main port, 4000),
4. makes one anonymous `POST /api/ping` and checks it reports `status: UP`,
5. records the outcome into [`matrix.json`](./matrix.json).

**Limits of v0 — on purpose:**

- The ping is **anonymous**, so it proves *reachability* ("this suite boots and
  serves the API"), not the wire contract version. Reading `apiVersion` needs an
  authenticated call (`X-API-Key`), and API keys are provisioned via a mediator
  command, not a public endpoint — so authenticated version verification is a
  later step.
- The `contract` label on the cell is taken from `gradle/libs.versions.toml`
  (the version this suite build pins), **not** independently read over the wire.
- Only the **co-released pairing** is exercised (suite built against its pinned
  contract). Cross-version client skew (an older client against a newer suite —
  the real point of a matrix) comes once we can vary the client contract version.

## Run one cell locally

```bash
# against a published image, deriving version labels from the repo:
IMAGE=ghcr.io/<owner>/epistola-suite:latest compatibility/smoke.sh

# or fully explicit:
compatibility/smoke.sh \
  --image ghcr.io/<owner>/epistola-suite:1.0.0 \
  --suite 1.0.0 --contract 0.10.0 \
  --out compatibility/matrix.json
```

Requires `docker`, `curl`, `jq`. Exit code is `0` on a passing cell, non-zero
otherwise. The updated `matrix.json` is the single source of truth; a
human-readable table is rendered from it (later).

## Roadmap (each an independent, shippable step)

1. **CI wiring** — a release-triggered workflow that runs `smoke.sh` against the
   freshly-published image. *(next commit)*
2. **Authenticated version check** — provision a key, read `apiVersion` from
   `/api/ping` details, and assert it matches the cell's contract version.
3. **Vary the client** — run the smoke against a range of contract versions
   (fixed suite image, varying client), turning one cell into a real row.
4. **Render** a human-readable table from `matrix.json`.
5. **Commit results back** from CI; later, publish as a feed.
6. **Plugin (transitive)** — infer `valtimo-epistola-plugin` compatibility from
   its declared supported contract range against verified suite↔contract cells.
