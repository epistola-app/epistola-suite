package app.epistola.suite.common

/**
 * Opt-out marker for commands that must **not** produce an audit-log entry.
 *
 * The audit recorder records every command dispatched through the mediator
 * except those that are `SystemInternal` (background/system work) or carry this
 * marker. Use `NotAudited` for ordinary, user-driven commands that are
 * nonetheless too high-frequency or low-value to belong in a "who did what,
 * when" trail (e.g. best-effort housekeeping writes, or high-volume work that is
 * already tracked in full elsewhere, like document generation).
 *
 * This is the **write-side opt-out**; reads are the opposite polarity (opt-in via
 * [AuditedRead]). Both are compile-time marker interfaces — the same idiom as
 * [TenantScoped] / [EntityIdentifiable] — interpreted by the audit feature
 * module, which depends on core; the markers live here so core domain types can
 * declare them without a dependency cycle.
 */
interface NotAudited
