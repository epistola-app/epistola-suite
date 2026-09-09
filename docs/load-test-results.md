<!--
SPDX-FileCopyrightText: Epistola Nederland B.V.

SPDX-License-Identifier: AGPL-3.0-only
-->

# Load test results

> **Status:** A running log of `epistola-load-test` runs, so the effect of a change on
> throughput is visible over time. Point-in-time measurements on named hardware, not
> guarantees — re-measure before relying on them.

Numbers are only comparable **within** a hardware profile. Record the profile tag with every
run; a figure without one is not a data point. Add a row per run worth keeping — a release, a
change that touches the generation or persistence path, or a suspected regression.

For the collect protocol's own limits see [`collect-performance.md`](collect-performance.md);
for the in-app load-test feature see [`loadtesting.md`](loadtesting.md).

## Hardware profiles

Defined once, referenced by tag. Add a profile rather than editing one — an edited profile
silently invalidates every row that referenced it.

| Tag      | Host                                                | Container runtime                                     | Notes                                                                                                                 |
| -------- | --------------------------------------------------- | ----------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------- |
| `m1p-32` | Apple M1 Pro, 10 cores (8P/2E), 32 GB RAM, macOS 26 | Podman machine: 5 vCPU, 7.45 GB, disk-backed Postgres | Dev laptop. All nodes, Postgres and the driver share it — expect high variance and treat absolute figures as a floor. |

## Results

`e2e` is submit → generated → delivered over collect, which is the number that reflects real
capacity. `submit` is how fast the API accepts work, and always runs ahead.

| Date       | Profile  | Build                                                              | Setup                                    | Target | submit (docs/min) | e2e (docs/min) | peak (docs/min) | Notes                                                                                                                                                                   |
| ---------- | -------- | ------------------------------------------------------------------ | ---------------------------------------- | ------ | ----------------- | -------------- | --------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 2026-09-09 | `m1p-32` | `feat/catalog-resource-relocation-alpha` @ schema `20260905091000` | 3 nodes + proxy, concurrency 48, batch 1 | 20 000 | 16 574            | ~7 660         | 19 272          | First run on the identity-keyed schema. All 20 000 COMPLETED, 0 unacked. No `main` baseline on this profile, so this establishes a reference point rather than a delta. |

### Reference points

- `deployment.md` quotes ~5 000 docs/min **per node** on a 4-core production chart profile.
  Three nodes on `m1p-32` reaching ~7 660 e2e is consistent with a laptop sharing all roles,
  not a regression signal.
- A second driver track (`volume`, 20 000 docs) measured ~10 300 docs/min on the same run —
  submit-and-drain without the collect consumer in the loop.

## Running one

Needs a cluster with a tenant and an API key. The multi-instance harness gives three nodes of a
local build behind a proxy; point it at the **demo** distribution, which is what seeds them.

```bash
./gradlew :apps:epistola-demo:bootJar
MIT_JAR_DIR="$PWD/apps/epistola-demo/build/libs" MIT_PROFILES="local,demo" \
  SKIP_BUILD=1 scripts/multi-instance-test.sh up

BIN=../epistola-load-test/build/install/epistola-load-test/bin/epistola-load-test
$BIN bench --base-url http://127.0.0.1:4444 --tenant demo \
  --api-key epk_demo_000000000000000000000000000000000000 \
  --target 20000 --concurrency 48 --batch-size 1

scripts/multi-instance-test.sh down
```

**Confirm you measured what you think you did.** The jar and the schema are both easy to get
wrong, and a run against the previous build passes and looks normal:

```sql
SELECT count(*), max(version) FROM flyway_schema_history WHERE success;
```

## Caveats worth repeating

- **A single profile proves capacity, not a delta.** To attribute a change you need the same
  test on the same machine before and after; absolute figures across machines say nothing.
- **`bench` can under-report delivery.** A run may finish with e.g. `delivered=19995/20000`
  while the database shows every result delivered and acknowledged — check
  `generation_results` against `consumer_partition_cursors` before treating it as loss.
