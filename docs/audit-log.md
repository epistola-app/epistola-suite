# Audit log

The audit log is a **PII-free, append-only, forever-retained** record of
"who did what (and read what), when" — the write commands and the deliberately
chosen sensitive reads dispatched through the mediator, with their outcome. It
exists so an operator can answer _who changed or accessed this, and when_
indefinitely, without the trail ever becoming a privacy liability that must be
purged.

It is deliberately **distinct** from the two adjacent logs:

| Store             | Contents                                 | Retention   | Contains PII?           |
| ----------------- | ---------------------------------------- | ----------- | ----------------------- |
| `audit_log`       | curated command metadata (this document) | **forever** | **no, by construction** |
| `event_log`       | full command payloads (future replay)    | forever     | **yes** (payload dump)  |
| `application_log` | raw Logback output                       | ~1 week     | incidental              |

`event_log` is **not** an audit log: it serializes the whole command (emails,
names, …) into a JSONB payload. The audit log stores none of that.

## What a row contains

`audit_log` (see the migration `audit/V20260622102813__audit_audit_log.sql` in the
`epistola-audit` module):

| Column          | Meaning                                                                                           |
| --------------- | ------------------------------------------------------------------------------------------------- |
| `id`            | UUIDv7 (time-ordered; stable id for future hub forwarding)                                        |
| `occurred_at`   | when the command was dispatched (`EpistolaClock`) — the partition key                             |
| `tenant_key`    | tenant slug, or `NULL` for system/root commands. **Not a foreign key.**                           |
| `actor_user_id` | opaque surrogate id of the actor. **Not a foreign key.** `NULL` if unauthenticated                |
| `action`        | command/query class simple name, e.g. `CreateTheme`, `GetDocument`                                |
| `operation`     | `WRITE` (an audited command) or `READ` (an audited data-access query)                             |
| `entity_type`   | entity kind, e.g. `template`, `variant`, `environment` (from the typed id's `path()`)             |
| `entity_id`     | reference to the entity acted on (the typed id's path segments, or `EntityIdentifiable.entityId`) |
| `outcome`       | `SUCCESS` or `FAILURE`                                                                            |
| `error_code`    | on failure: `ValidationCode` name or exception class — **never the message**                      |
| `instance_id`   | `NodeIdentity.nodeId` that recorded the entry                                                     |

There is **no payload and no free text**, so a command field value can never
leak into the table (enforced by a test). `error_code` is a machine code, not a
message, because messages can echo user input.

**Tenant and entity are derived generically**, so a command/query needn't opt in:
`tenant_key` comes from `TenantScoped.tenantId` _or_ `RequiresPermission.tenantKey`
(most are permission-gated, not `TenantScoped`); `entity_type`/`entity_id` come
from the first typed `EntityId` property on the message (its `path()` —
`"<type>:<segments>"`, e.g. `template:acme/default/invoice`), falling back to an
explicit `EntityIdentifiable`. Identifiers/slugs are not personal data.

## Why it never needs deletion (PII-free + pseudonymization)

`actor_user_id` and `tenant_key` are opaque surrogate ids with **no foreign
key**, so audit rows survive user/tenant deletion. The acting user's display
name is resolved only at **read time**, via a `LEFT JOIN users` in
`ListAuditEntries`, purely for the viewer; the stored row holds no name or
email. A future GDPR erasure scrubs the PII in the `users` / `tenants` tables —
after which a surrogate no longer resolves to a person, and the viewer renders
"(deleted user)". The audit row itself is never personal data and is never
deleted.

## How rows are written

`AuditRecorder` is both a **`CommandListener`** and a **`QueryListener`** — the
mediator's generic seams for cross-cutting observers of dispatch. `SpringMediator`
notifies every registered listener on **both** the success and failure paths;
the command seam is the only one that sees both outcomes (the typed `EventHandler`
and the `CommandCompleted` event both fire on success only, and the latter rolls
back with the command). Listeners are isolated — one that throws is logged and
skipped, affecting neither the dispatch nor the other listeners.

Each entry is written in its **own `REQUIRES_NEW` transaction** — the command's
transaction is suspended, the audit row commits (or rolls back) on an independent
connection, then the command's transaction resumes. This is essential: the
primary JDBI is transaction-aware (`SpringConnectionFactory`), so writing on it
would join the command's transaction and a failed audit write would **abort the
command** (and, via nested dispatch, an outer command). Isolating it means a
failing command's rollback can't erase its FAILURE entry, and an audit-write
failure can't affect the command. Recording is best-effort: it never throws, and
persistence failures are counted (`epistola.auditlog.persist.failures`) and
logged, mirroring `EventLogSubscriber`. (A cross-cutting listener fires after the
command's own transaction has already resolved, so there is no command
transaction to be atomic with — best-effort is the coherent model here; strict
atomicity would require a different mechanism such as DB triggers, which can't
record failures.)

Routing audit through the `CommandListener` / `QueryListener` SPIs (rather than a
hardcoded call) keeps the mediator unaware of "audit" specifically and is the seam
a future `epistola-audit` module — or a tracing / hub-forwarding listener — would
plug into without changing the mediator.

### What is and isn't recorded

Commands and queries have **opposite default polarity**:

- **Commands → opt-out** (`operation = WRITE`). Every command is recorded _except_
  `SystemInternal` ones (background/system work) and those marked `NotAudited`
  (the high-frequency opt-out). `NotAudited` covers `RecordApiKeyUsage` /
  `UpdateLastLogin` (housekeeping) and `GenerateDocument` /
  `GenerateDocumentBatch` (high-volume, and already tracked in full by the
  generation subsystem — they would bury the trail without adding much).
- **Queries → opt-in** (`operation = READ`). Queries are recorded _only_ when
  marked `AuditedRead`, because they are overwhelmingly high-volume internal
  reads (nav, feature toggles, list pages — several per page render). Only the
  handful of genuine, sensitive **data-access** reads are marked — currently
  `GetDocument` (retrieving a stored document). Browse/list/preview queries are
  deliberately left unmarked.

The mediator notifies cross-cutting listeners for _every_ command and query; the
audit listener applies the polarity above. So adding a read to the trail is a
one-line `AuditedRead` marker on the query, and removing command noise is a
one-line `NotAudited` marker.

If a genuinely useful **system** action should be audited despite being
`SystemInternal`, introduce a small opt-in marker (e.g. `AlwaysAudited`) and
honour it in `AuditRecorder` — added when the first such case appears.

## Storage: monthly partitioning, kept forever

Because the table is retained forever and accrues a row per write, it is
**`PARTITION BY RANGE (occurred_at)`**, monthly — the same pattern as the
`documents` tables. Core's `PartitionMaintenanceScheduler` creates the current +
next month partitions at startup and monthly thereafter; the audit module declares
`audit_log` to it via an `AuditPartitionedTables` (`PartitionedTableContributor`)
bean with `retentionMonths = null`, so its partitions are **created but never
auto-dropped**. Partition pruning keeps the newest-first viewer and time-bounded
queries fast no matter how much history accumulates.

Cold archival (detaching very old partitions and moving them to cheap storage,
optionally batched — never deleted) is a deliberate **future tier**;
partitioning is what makes detaching clean.

The audit module also declares `audit_log` **excluded** from tenant backup/restore
via an `AuditBackupTables` (`TenantBackupTableContributor`) bean, so a restore never
reads or rewrites audit history. Its migration carries a
`backup-restore-compatibility` header (backward+forward — additive, touches no
backed-up table) so restores cross it cleanly.

## Surfaces

The audit log is **UI-only** for now: a permission-gated viewer at
`/tenants/{tenantId}/audit` (`AuditHandler` / `AuditRoutes`,
`templates/audit/list.html`), gated by the `AUDIT_VIEW` permission (granted to
`TENANT_ADMINISTRATOR`) and surfaced as an **Operations** nav item. This mirrors
the `/profile` page precedent of keeping a sensitive read off the REST and MCP
surfaces. Exposing it on REST (e.g. SIEM pull) or as an MCP tool is a
deliberate follow-up, not a silent gap.

## Module layout

The feature lives in its own module, **`epistola-audit`** (recorder, read model +
`ListAuditEntries`, the `audit_log` migration, the UI handler/routes/templates +
nav contributor, and the partition/backup table contributors). Only the **contract**
stays in `epistola-core`, because core domain types depend on it:

- the `CommandListener` / `QueryListener` SPIs (the mediator owns them),
- the `NotAudited` / `AuditedRead` markers (in `app.epistola.suite.common`, applied
  by core commands/queries — they can't live in the module without a dependency
  cycle), and
- the `AUDIT_VIEW` permission (a closed enum in core).

The module depends on core + `epistola-web`; the host app depends on the module so
Spring picks up its beans, templates, and migration. This mirrors
`epistola-support-feedback`.

## Demo catalog

The audit log is cross-cutting infrastructure, not a catalog resource
(template/theme/stencil/data-contract), so it cannot be represented in the demo
catalog — the explicit exemption called for by the demo-catalog requirement.
