package app.epistola.suite.mediator

/**
 * Cross-cutting observer of every command the [Mediator] dispatches, on **both**
 * the success and failure paths.
 *
 * This is the generic seam for concerns that need to see all command (write)
 * activity regardless of type — auditing, tracing, cross-cutting metrics, future
 * hub forwarding. It is distinct from, and complementary to, the existing
 * mechanisms:
 *
 * - [EventHandler] is typed per command and fires on **success only** (with the
 *   handler result, and IMMEDIATE handlers can roll the command back).
 * - [CommandCompleted] is a Spring event published on **success only**.
 *
 * A `CommandListener`, by contrast, is untyped, sees **failures too**, and is
 * called synchronously by [SpringMediator]. The mediator collects every
 * `CommandListener` bean and notifies each one. See [QueryListener] for the
 * read-side equivalent.
 *
 * Contract: implementations are side-effect-only and **must not throw** — the
 * mediator isolates and logs listener errors so a misbehaving listener can
 * neither affect the command nor the other listeners. Heavy work should be done
 * in the listener's own transaction/connection (see `AuditRecorder`), never on
 * the command's.
 */
interface CommandListener {
    /**
     * Notified after [command] has been dispatched. [error] is the thrown
     * exception when [outcome] is [DispatchOutcome.FAILURE], else null.
     */
    fun onCommand(
        command: Command<*>,
        outcome: DispatchOutcome,
        error: Throwable?,
    )
}
