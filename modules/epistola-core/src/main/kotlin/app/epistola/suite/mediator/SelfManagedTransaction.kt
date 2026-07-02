package app.epistola.suite.mediator

/**
 * Opt-out marker for [Command]s that must NOT be wrapped in the mediator's per-command
 * database transaction.
 *
 * The mediator wraps every command dispatch (handler + IMMEDIATE event handlers + event
 * publication) in one Spring transaction so the documented atomicity contract holds.
 * A command should opt out only when its handler:
 *
 * - performs external I/O mid-command (HTTP calls to catalogs, code-list sources, the
 *   hub) — holding a database connection open across a slow network call risks pool
 *   exhaustion, or
 * - deliberately commits in stages (e.g. recording a fetch error must survive the
 *   command failing afterwards).
 *
 * Opted-out handlers own their transaction boundaries via `jdbi.inTransaction { }`.
 * Note that IMMEDIATE event handlers for such commands run AFTER the handler's own
 * transaction has committed and can no longer roll the command back, and
 * AFTER_COMMIT event handlers run immediately at publication (no transaction to bind
 * to). Prefer wrapping; opt out only for the reasons above and say why at the use site.
 */
interface SelfManagedTransaction
