package app.epistola.suite.mediator

/**
 * Cross-cutting observer of every query the [Mediator] dispatches — the read-side
 * counterpart of [CommandListener], for "who read what" concerns (data-access
 * auditing, tracing).
 *
 * The mediator notifies listeners for **every** query, but because queries are
 * overwhelmingly high-volume internal reads (nav, feature toggles, list pages —
 * several per page render), a listener is expected to record only a deliberately
 * chosen subset. The audit listener, for example, ignores everything except
 * queries marked [app.epistola.suite.common.AuditedRead].
 *
 * Same contract as [CommandListener]: side-effect-only, **must not throw** (the
 * mediator isolates and logs listener errors), heavy work off the query's own
 * connection.
 */
interface QueryListener {
    /**
     * Notified after [query] has been dispatched. [error] is the thrown
     * exception when [outcome] is [DispatchOutcome.FAILURE], else null.
     */
    fun onQuery(
        query: Query<*>,
        outcome: DispatchOutcome,
        error: Throwable?,
    )
}
