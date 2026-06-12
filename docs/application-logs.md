# Application logs

Epistola persists its **application log output** (Logback events) into a bounded,
queryable database table so operators can see what an instance is doing without
shell access to the container or an external log stack. Logs are retained for a
short window (default one week), viewable per tenant in the UI, and the schema is
shaped so they can later be forwarded to **epistola-hub** for fleet aggregation.

> This is distinct from the **`event_log`** table (`EventLogSubscriber`), which
> is a command-completion **audit trail** (domain events), not logger output.

## Storage

Table `application_log` (migration `core/V20260612053608__core_application_log.sql`):

| column        | notes                                                        |
| ------------- | ------------------------------------------------------------ |
| `id`          | UUIDv7 (time-ordered, generated in-app); stable forward id   |
| `occurred_at` | event timestamp (not the DB clock)                           |
| `level`       | ERROR / WARN / INFO / DEBUG / TRACE                          |
| `logger`      | logger name                                                  |
| `message`     | formatted message                                            |
| `thread`      | producing thread name (nullable)                             |
| `instance_id` | `NodeIdentity.nodeId` — which instance produced the event    |
| `tenant_key`  | tenant the event was for; **NULL** for system/background     |
| `trace_id`    | from MDC, when present                                       |
| `span_id`     | from MDC, when present                                       |
| `exception`   | rendered stack trace, when the event carried a throwable     |
| `attributes`  | remaining MDC as JSONB (excludes trace/span, promoted above) |

It is a **plain (non-partitioned) table** with indexes on `occurred_at`,
`(tenant_key, occurred_at)` and `(level, occurred_at)`. Weekly volume is expected
to be low, so a single indexed table plus a nightly `DELETE` is sufficient and the
`occurred_at` index keeps both the retention delete and the newest-first viewer
cheap. `tenant_key` is intentionally **not** a foreign key — the log is append-only
and must survive tenant deletion and stay forwarding-friendly.

If volume ever warrants it, the table can be converted to native **daily RANGE
partitioning** (mirroring the documents partition approach in
`PartitionMaintenanceScheduler`) and pruned by dropping partitions, without
changing the read/write contracts.

## Capture (non-blocking, batched, fail-open)

Capture bridges Logback (instantiated by the logging system) and Spring (owns
`Jdbi`/`NodeIdentity`):

- **`ApplicationLogAppender`** (`AppenderBase<ILoggingEvent>`) converts each event
  into an immutable `ApplicationLogRecord` **on the thread that logged**, because
  the per-request tenant context (`SecurityContext`, a `ScopedValue`) and the MDC
  are only bound on that thread. It then `offer`s the record to a bounded queue —
  never blocking the caller, never throwing into it.
- **`ApplicationLogIngestor`** owns the bounded `ArrayBlockingQueue` and a single
  daemon worker that drains up to `batch-size` records per JDBI `prepareBatch`
  insert. `instance_id` is stamped at drain time from `NodeIdentity`.

Guarantees:

- **Non-blocking** — `enqueue` only `offer`s; on overflow it drops the event and
  increments `epistola.logs.dropped`.
- **Fail-open** — a failing batch is dropped and counted
  (`epistola.logs.persist.failures`), never retried in a tight loop, and never
  allowed to kill the worker. A DB outage degrades logging silently rather than
  breaking requests.
- **No recursion** — the appender ignores events from this feature's own package
  (`app.epistola.suite.logs`), so a persistence-failure log can't re-enter the
  queue.
- **Level threshold** — a Logback `ThresholdFilter` (default `INFO`) bounds volume.
- **Log-bomb guard** — a token bucket at `enqueue` caps capture to
  `epistola.logs.max-rate-per-second` (default 2000; `0` disables). The bucket
  starts full so normal bursts pass, but a _sustained_ flood from a runaway logger
  is shed and counted (`epistola.logs.rate-limited`) **before** it reaches the queue
  — bounding both table/disk growth and DB write load, regardless of how fast the
  application logs. The bounded queue (memory) and this rate cap (DB/disk) are
  complementary: the queue caps a burst, the rate cap caps a sustained flood.

The appender is attached programmatically on `ApplicationReadyEvent` (after Flyway,
mirroring `PartitionMaintenanceScheduler`) and detached on shutdown, so there is no
"before Spring is ready" window where events drop for lack of a sink. The worker
flushes whatever is queued on shutdown.

### Tenant attribution

`tenant_key` comes from a **single source**: the active principal's `currentTenantId`
(`SecurityContext`, a `ScopedValue`) read on the logging thread. There is no separate
"current tenant" mechanism and no MDC fallback — the principal already carries the
tenant and is propagated across executor / virtual-thread handoffs by
`MediatorContext.runnable/callable`. So:

- **Request logs** attribute to the request's tenant (the `SecurityFilter` binds the principal).
- **Background work** attributes when it binds a system principal for its tenant —
  `SystemUser.principalForTenant(tenantKey)` (the job poller, snapshot backup/upgrading
  schedulers, feedback sync, and tenant-scoped cluster scheduled tasks/timers).
- **System/background work with no principal** (system-wide cluster tasks like partition
  maintenance and log retention) → `tenant_key = NULL`, shown as **system**.

A log line is "tenant X" because the work that emitted it runs as X — not because the
message mentions X. (Making _all_ background work run under a system identity is a
tracked consistency follow-up, issue #551.)

## Retention

`ApplicationLogRetentionScheduler` is a `SINGLE_OWNER` cluster scheduled task
(`core.application-log-retention`) — exactly one capable node runs each occurrence,
so it is multi-pod safe without any extra advisory lock. It runs on
`epistola.logs.retention-cron` (default 03:30 UTC daily) and deletes rows older than
`epistola.logs.retention-days`.

## Configuration

All under `epistola.logs.*` (`ApplicationLogProperties`):

| property              | default        | meaning                                                      |
| --------------------- | -------------- | ------------------------------------------------------------ |
| `enabled`             | `true`         | master switch; when false no appender is attached            |
| `level`               | `INFO`         | minimum level captured                                       |
| `retention-days`      | `7`            | rows older than this are pruned                              |
| `queue-capacity`      | `10000`        | bounded queue size; overflow is dropped                      |
| `max-rate-per-second` | `2000`         | log-bomb guard: max events captured/s, then shed (`0` = off) |
| `batch-size`          | `200`          | max records per batched insert                               |
| `flush-interval-ms`   | `1000`         | max wait for the first record before looping                 |
| `retention-cron`      | `0 30 3 * * ?` | retention schedule (UTC)                                     |

> The integration-test profile sets `epistola.logs.enabled=false` so the suite does
> not attach the DB appender on every test. The capture/retention tests construct
> `ApplicationLogIngestor` / `ApplicationLogRetentionScheduler` directly and drive
> them via `flush()` / `deleteExpired()`, so capture is exercised deterministically
> without the background worker's timing and without polluting the table.

## Performance (load test)

`ApplicationLogIngestPerfTest` (opt-in, `@Tag("perf")`) answers _how many log
messages can we persist per second via batched inserts while keeping the database
unstrained?_ It drives the real path (`enqueue` → `flush()` → JDBI `prepareBatch`)
with one drain thread (the production worker) fed by virtual-thread producers, and
captures throughput, effective batch size, and per-`INSERT` latency (the DB-strain
signal) via a JDBI `SqlLogger`.

For isolation it boots a **purpose-built slim Spring context** (`LogIngestPerfContext`
— DataSource + Flyway + JDBI only, no `@SpringBootApplication`/component scan), so no
cluster schedulers / JobPoller / MCP / generation beans contend for the DB or CPU. The
shared Testcontainers Postgres is tmpfs-backed (disk = RAM-speed), so numbers are
optimistic vs. real disk but consistent for comparison.

Two scenarios: a **batch-size sweep** (`{50, 200, 500, 1000}`, 200k records, no drops —
the single writer's sustained rate and the throughput/latency trade-off) and an
**overload** case (1M records, bounded 10k queue) showing the bounded queue **shedding
excess via drops** while `persistFailures == 0` and every record is accounted for.
Report-only plus those sanity assertions; results print to the console and append to
`build/perf-reports/application-log-ingest.csv`.

```
./gradlew :modules:epistola-core:perfTest \
  --tests "*ApplicationLogIngestPerfTest" -Dperf.hardware=<tag>
```

## UI

A tenant-scoped **Operations → Logs** page (`/tenants/{tenantId}/logs`,
`LogsHandler` + `templates/logs/list.html`) lists the tenant's rows **plus system
rows with no tenant** (`tenant_key = :tenantId OR tenant_key IS NULL`), newest
first. It is gated on `Permission.TENANT_SETTINGS` and reads through the
permission-gated `ListApplicationLogs` CQRS query (no JDBI in the handler).

The dense, page-wide table shows the **newest 20** rows by default. Filters
(free-text message search, level, logger, and a date/time range) re-render the
results region via HTMX. **Times are displayed in the browser's timezone**: each
cell carries the ISO instant in `data-ts` and a CSP-safe inline script formats it
client-side (re-running after every HTMX swap; the server-rendered UTC text is the
no-JS fallback). The browser's IANA zone is sent as a hidden `tz` field so the
**From/To** wall-clock values are interpreted in that same zone (UTC when absent). Incremental paging is **keyset (cursor) based** on
`(occurred_at, id)` — stable across ties and unaffected by rows arriving while
you browse:

- **`ListApplicationLogs`** takes a `direction` (`OLDER`/`NEWER`) and an
  `(occurred_at, id)` cursor, and always returns rows newest-first.
- The viewer renders two self-replacing HTMX sentinel rows: **Load newer** (top)
  `hx-get`s `/logs/newer` and prepends rows above; **Load older** (bottom)
  `hx-get`s `/logs/older` and appends rows below. Each response carries a
  refreshed sentinel with the next cursor (the older sentinel drops away when
  history is exhausted), so the table grows in place with no offset drift and no
  client-side JavaScript. The logger column shows the simple class name
  (`ApplicationLogEntry.shortLogger`) with the full name on hover.

## Other surfaces

- **REST API** — deferred. No `/api/.../logs` endpoint yet.
- **MCP** — deferred. A read-only "recent logs" tool for AI-assisted ops is
  plausible later.
- **Hub forwarding (`LogSyncPort`)** — not built. The core table is the source of
  truth; stable `id` (UUIDv7), `instance_id` and `occurred_at` make rows
  forward-friendly. A future `epistola-support` `LogSyncPort` +
  `HubLogSyncAdapter` + no-op fallback (gated on `epistola.support.enabled`) would
  ship rows, mirroring the `FeedbackSyncPort` / snapshot-sync pattern.
