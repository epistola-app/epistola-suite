# Interactive generation: load impact

> **Status:** Record, 2026-11-19. Measured on branch `feat/data-collection-form` against its base
> on `main` (`8cb63674c`). True when written, not maintained.

What interactive generation (contract ADR 0011, alpha) costs the generation pipeline. There are two
questions:

1. **What does every installation pay, even when nobody uses a form?** The feature touches hot
   paths: acceptance, the result emitted for every document, and the job status callers poll.
2. **What does a request with a form cost** at acceptance, while it waits, and on the form page?

## How it was measured

- **Rig.** The demo app (`demo,local,localauth`), one node, on a developer laptop with
  `-Xmx2g`, against its own PostgreSQL 18.6 container with `pg_stat_statements`. Baseline and
  branch ran from identical bootJars of each commit, each on a fresh database, one at a time.
  Template: the demo catalog's `hello-world`.
- **Load.** A small JDK HTTP driver (virtual threads) submitted 2000 `generateDocument` requests at
  concurrency 16 after a 300-request warm-up. It waited until every job had finished, then polled
  one job's status 4000 times at concurrency 16. The scripts are not committed: they are a few
  lines each, and the rig is a laptop.
- **Matrix.** Two rounds, interleaved: baseline; branch without `interactive` (`plain`); branch
  with `trigger: incomplete` and complete data, so every job is analysed at acceptance and then
  renders; branch with `trigger: always`, so every job waits for a form.
- **Noise.** The machine was shared (load average 8–10, other containers running). Three
  identical baseline runs before the matrix gave 3,014, 6,442 and 9,626 documents a minute. Wall
  clock numbers can show only a gross regression. **The database statistics are the reliable
  signal:** statements and database time per request do not depend on what else the CPU was doing.

## Results

| Run          | Submit p50 / p95 (ms) | Render (docs/min) | Statements per doc | `generation_results` INSERT (ms/doc) | Status poll: statements, DB ms |
| ------------ | --------------------- | ----------------- | ------------------ | ------------------------------------ | ------------------------------ |
| base-1       | 38 / 91               | 4,781             | 61.9               | 0.098                                | 9, 12.9                        |
| base-2       | 51 / 141              | 4,259             | 61.8               | 0.110                                | 9, 13.1                        |
| plain-1      | 37 / 87               | 4,613             | 61.9               | 0.141                                | 10, 15.7                       |
| plain-2      | 42 / 91               | 4,880             | 61.7               | 0.126                                | 10, 13.5                       |
| incomplete-1 | 55 / 124              | 4,379             | 69.2               | 0.148                                | 10, 13.5                       |
| incomplete-2 | 47 / 90               | 4,697             | 68.9               | 0.135                                | 10, 13.0                       |
| always-1     | 39 / 86               | (waits)           | 41.1               | —                                    | 10, 14.2                       |
| always-2     | 36 / 75               | (waits)           | 41.1               | —                                    | 10, 12.6                       |

Other measurements:

- **Preview** of `hello-world` on the form page, 20 serial requests: p50 24 ms, max 36 ms. A worker's
  render of the same template took 15 ms (p50 over 9,200 documents).
- **Expiry sweep** with 9,200 interaction rows: 1.5 ms when nothing is due, 25 ms to cancel a batch of
  500 due forms (out of 4,600 waiting).

## Findings

### What every installation pays

1. **Acceptance without `interactive`: nothing measurable.** No extra statement (61.9 per document
   both ways); the handler checks for `interactive` before anything else.
2. **Every emitted result does two lookups on the interaction table** (`EmitGenerationResult`
   copies `data` and `editedFields` for a job with a form). The INSERT went from 0.10–0.11 ms to
   0.13–0.15 ms per document, about +0.03 ms, consistently over four branch runs. The plan shows why
   it will grow: the lookup is by request id, which cannot be narrowed to one month, so it probes the
   primary-key index of **every** monthly partition of both tables. That is 4 probes on the rig, and
   about 8–10 at the default retention of 3 months. Small per document, paid by every document.
3. **Every job-status poll does one extra query** (`InteractiveStates.attach`, 9 → 10 statements),
   about 0.01 ms, also for a job without a form. Callers poll this endpoint, so it adds up with them.
4. **The expiry sweep reads the whole interaction table every minute.** With nothing due, the
   planner drives from the interaction table and scans it (1.5 ms for 9,200 rows). That grows with
   every form kept for the retention period (3 months), submitted ones included, even when no form
   is waiting.

### What a request with a form costs

5. **`trigger: incomplete` analyses the data at acceptance by resolving the bound release again:**
   +7 statements (template, theme, release resources, rendering defaults), the same work a worker
   does when it renders. Submit p50 rose from about 40 ms to 47–55 ms. Render throughput was within
   noise. With `trigger: always` acceptance is cheap (41 statements, no render follows).
6. **A preview costs about 1.6 renders, on the app node, outside the queue.** It resolves the release
   on every call and renders on the request thread, so it bypasses the job queue's admission control
   (`DatabasePressureAdmissionController`). The limit is per form (one at a time, 20 a minute); there
   is no limit over all forms, so 100 people previewing at once could ask the node for about 33
   renders a second.
7. **A waiting job costs nothing while it waits.** Workers claim only `PENDING` rows (partial index),
   and the stale-job recovery touches only `IN_PROGRESS`.

### Not this feature, found along the way

8. **`UPDATE api_keys SET last_used_at = NOW()` dominates database time per API request**:
   13–28 ms of the 13–31 ms each request spent in the database, in the baseline as much as on the
   branch. Every request updates its key's row (`RecordApiKeyUsage`). With 16 concurrent requests on
   one key, they wait for each other on that row lock. A caller's throughput through one key is bound
   by it, with or without forms. Worth its own issue: throttle the write, or record usage
   asynchronously.

## What to do

| #    | Change                                                                                                                          | Why                                                                                                                              | When                                             |
| ---- | ------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------ |
| 2, 3 | Mark a request that has a form (a boolean on `document_generation_requests`), and look up the interaction table only for those. | Takes findings 2 and 3 to zero for every request without a form. The migration has not been released, so the column can join it. | Before merge                                     |
| 4    | Drive the sweep from the waiting requests, which a partial index on status finds directly, instead of from every interaction.   | Keeps the idle sweep constant instead of growing over the retention period.                                                      | Before merge, verified with a plan at 100k+ rows |
| 6    | A node-wide bound on concurrent previews (a semaphore sized like the render pool), refusing with 429 beyond it.                 | Stops previews from competing with the queue's renders without limit.                                                            | Before the feature leaves alpha                  |
| 5    | Reuse the resolved release inputs between acceptance and the worker, or analyse against a cheaper contract read.                | 10–15 ms per interactive acceptance. Acceptable for alpha.                                                                       | Later, if it matters                             |
| 8    | Its own issue.                                                                                                                  | Not this feature.                                                                                                                | Separately                                       |

## Not measured here

- A cluster: several nodes, collect over several consumers, the shared queue. The standalone load
  tool (`epistola-load-test`) still builds against contract `2.0.0-alpha.3` and cannot parse the
  current document list, so its `bench` and `collect` did not run. It needs a client bump first.
- Large `data` payloads on result rows and collect lines: the copy of `data` grows a result row by the
  size of the data, for jobs with a form only.
- Many partitions. The rig had two months of partitions. Findings 2 and 4 grow with that number, and
  only the plans show it.
- Many concurrent previews, and how they hurt the queue's renders on the same node.
