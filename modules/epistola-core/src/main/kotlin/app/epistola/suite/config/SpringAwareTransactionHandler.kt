// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.config

import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.HandleCallback
import org.jdbi.v3.core.transaction.LocalTransactionHandler
import org.jdbi.v3.core.transaction.TransactionHandler
import org.jdbi.v3.core.transaction.TransactionIsolationLevel
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.sql.Savepoint

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
 * - Spring transaction active: `jdbi.inTransaction`/`useTransaction` become a
 *   **JDBC savepoint** inside the surrounding transaction. Success releases the
 *   savepoint; failure rolls back *to* the savepoint before rethrowing. This preserves
 *   the semantics handlers were written against — a caught failure of one chunk (e.g.
 *   one resource in a catalog install loop) must not poison the surrounding
 *   transaction (PostgreSQL aborts the whole transaction on any statement error until
 *   a rollback) — while never committing the outer transaction early.
 *
 * [isInTransaction] intentionally reports only JDBI-managed transaction state, not the
 * Spring one: JDBI's `Handle.close()` treats a still-open JDBI transaction as a leak
 * (forceEndTransactions) and must keep seeing Spring-joined handles as clean.
 */
class SpringAwareTransactionHandler private constructor(
    private val delegate: TransactionHandler,
) : TransactionHandler {

    constructor() : this(LocalTransactionHandler.binding())

    /**
     * Savepoints opened by begin() while joined to a Spring transaction, per specialized
     * (per-handle) handler instance. A stack because JDBI may nest begin() calls.
     */
    private val springSavepoints = ArrayDeque<Savepoint>()

    private fun springTransactionActive(): Boolean = TransactionSynchronizationManager.isActualTransactionActive()

    override fun begin(handle: Handle) {
        if (springTransactionActive()) {
            springSavepoints.addLast(handle.connection.setSavepoint())
        } else {
            delegate.begin(handle)
        }
    }

    override fun commit(handle: Handle) {
        if (springTransactionActive()) {
            springSavepoints.removeLastOrNull()?.let { handle.connection.releaseSavepoint(it) }
        } else {
            delegate.commit(handle)
        }
    }

    override fun rollback(handle: Handle) {
        if (springTransactionActive()) {
            springSavepoints.removeLastOrNull()?.let { handle.connection.rollback(it) }
        } else {
            delegate.rollback(handle)
        }
    }

    override fun isInTransaction(handle: Handle): Boolean = delegate.isInTransaction(handle)

    override fun savepoint(handle: Handle, savepointName: String) = delegate.savepoint(handle, savepointName)

    override fun rollbackToSavepoint(handle: Handle, savepointName: String) = delegate.rollbackToSavepoint(handle, savepointName)

    override fun releaseSavepoint(handle: Handle, savepointName: String) = delegate.releaseSavepoint(handle, savepointName)

    override fun <R, X : Exception> inTransaction(handle: Handle, callback: HandleCallback<R, X>): R = if (springTransactionActive()) {
        inSavepoint(handle, callback)
    } else {
        delegate.inTransaction(handle, callback)
    }

    override fun <R, X : Exception> inTransaction(
        handle: Handle,
        level: TransactionIsolationLevel,
        callback: HandleCallback<R, X>,
    ): R = if (springTransactionActive()) {
        // Joining the surrounding transaction: its isolation level applies.
        inSavepoint(handle, callback)
    } else {
        delegate.inTransaction(handle, level, callback)
    }

    private fun <R, X : Exception> inSavepoint(handle: Handle, callback: HandleCallback<R, X>): R {
        begin(handle)
        return try {
            val result = callback.withHandle(handle)
            commit(handle)
            result
        } catch (e: Throwable) {
            rollback(handle)
            throw e
        }
    }

    override fun specialize(handle: Handle): TransactionHandler = SpringAwareTransactionHandler(delegate.specialize(handle))
}
