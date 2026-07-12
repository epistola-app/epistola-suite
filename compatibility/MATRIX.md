# Epistola compatibility matrix

<!-- Generated from `compatibility/matrix.json` by `compatibility/render.sh`. Do not edit by hand. -->

Anchor: **epistola-contract** (the wire contract both the suite and external clients speak).
A cell is one **(suite, contract)** pairing, established by booting a published suite image and observing `/api/ping` — see [`README.md`](./README.md).

_Last generated: never._

_No cells recorded yet. Run `compatibility/smoke.sh` to add one._

### Legend

- **Result** — `✅ pass`: the suite booted and served `/api/ping`. `❌ fail` / `⚠️ error`: see the cell `detail` in `matrix.json`.
- **Declared range** — `[minCompatibleApiVersion .. apiVersion]` the suite reports it accepts, read from an authenticated `/api/ping`. `—` when the image predates the field.
- **In range** — whether the cell's contract falls inside the declared range. `—` when no range was read.
