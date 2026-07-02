package app.epistola.suite.config

import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.HandleCallback
import org.jdbi.v3.core.transaction.LocalTransactionHandler
import org.jdbi.v3.core.transaction.TransactionHandler
import org.jdbi.v3.core.transaction.TransactionIsolationLevel
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * JDBI transaction handler that joins an active Spring-managed transaction instead of
 * managing its own.
 *
 * The mediator opens a Spring transaction around every command dispatch (handler +
 * IMMEDIATE event handlers + event publication). Connections come from
 * [org.jdbi.v3.spring.SpringConnectionFactory], so every handle opened during the
 * dispatch is bound to that transaction's connection. Without this handler, a nested
 * `jdbi.inTransaction { }` would call `Connection.commit()` on the Spring-managed
 * connection and commit the surrounding command transaction mid-flight, silently
 * breaking the mediator's rollback contract.
 *
 * Behavior:
 * - No Spring transaction active: delegates to [LocalTransactionHandler] — JDBI manages
 *   begin/commit/rollback itself, exactly as before (schedulers, pollers, registries).
 * - Spring transaction active: `inTransaction`/`useTransaction` simply run the callback
 *   on the handle (joining the surrounding transaction); begin/commit/rollback become
 *   no-ops. Rollback is driven by exception propagation: the exception reaches the
 *   mediator, which rolls back the whole Spring transaction.
 *
 * [isInTransaction] intentionally reports only JDBI-managed transaction state, not the
 * Spring one: JDBI's `Handle.close()` treats a still-open JDBI transaction as a leak
 * (forceEndTransactions) and must keep seeing Spring-joined handles as clean.
 */
class SpringAwareTransactionHandler private constructor(
    private val delegate: TransactionHandler,
) : TransactionHandler {

    constructor() : this(LocalTransactionHandler.binding())

    private fun springTransactionActive(): Boolean = TransactionSynchronizationManager.isActualTransactionActive()

    override fun begin(handle: Handle) {
        if (!springTransactionActive()) delegate.begin(handle)
    }

    override fun commit(handle: Handle) {
        if (!springTransactionActive()) delegate.commit(handle)
    }

    override fun rollback(handle: Handle) {
        if (!springTransactionActive()) delegate.rollback(handle)
    }

    override fun isInTransaction(handle: Handle): Boolean = delegate.isInTransaction(handle)

    override fun savepoint(handle: Handle, savepointName: String) = delegate.savepoint(handle, savepointName)

    override fun rollbackToSavepoint(handle: Handle, savepointName: String) = delegate.rollbackToSavepoint(handle, savepointName)

    override fun releaseSavepoint(handle: Handle, savepointName: String) = delegate.releaseSavepoint(handle, savepointName)

    override fun <R, X : Exception> inTransaction(handle: Handle, callback: HandleCallback<R, X>): R = if (springTransactionActive()) {
        callback.withHandle(handle)
    } else {
        delegate.inTransaction(handle, callback)
    }

    override fun <R, X : Exception> inTransaction(
        handle: Handle,
        level: TransactionIsolationLevel,
        callback: HandleCallback<R, X>,
    ): R = if (springTransactionActive()) {
        // Joining the surrounding transaction: its isolation level applies.
        callback.withHandle(handle)
    } else {
        delegate.inTransaction(handle, level, callback)
    }

    override fun specialize(handle: Handle): TransactionHandler = SpringAwareTransactionHandler(delegate.specialize(handle))
}
