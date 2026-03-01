package app.epistola.suite.config

import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.Jdbi

/**
 * Kotlin extension functions to reduce JDBI boilerplate.
 *
 * These eliminate the need to specify `<T, Exception>` type parameters for every
 * withHandle/inTransaction call, improving readability and reducing noise.
 *
 * Before:
 * ```kotlin
 * jdbi.withHandle<List<Theme>, Exception> { handle ->
 *     handle.createQuery(...).mapTo<Theme>().list()
 * }
 * ```
 *
 * After:
 * ```kotlin
 * jdbi.withHandle { handle ->
 *     handle.createQuery(...).mapTo<Theme>().list()
 * }
 * ```
 */

/**
 * Execute a block with a JDBI handle, returning a result.
 *
 * Wraps the underlying `withHandle<T, Exception>` to eliminate type parameter noise.
 *
 * @param T The return type
 * @param block The block to execute with the handle
 * @return The result of the block
 */
inline fun <T> Jdbi.withHandle(crossinline block: (Handle) -> T): T = withHandle<T, Exception> { block(it) }

/**
 * Execute a block within a transaction, returning a result.
 *
 * All operations within the block run in a single transaction.
 * If an exception is thrown, the transaction is rolled back.
 *
 * @param T The return type
 * @param block The block to execute within the transaction
 * @return The result of the block
 */
inline fun <T> Jdbi.inTransaction(crossinline block: (Handle) -> T): T = inTransaction<T, Exception> { block(it) }

/**
 * Execute a block with a JDBI handle for side effects (no return value).
 *
 * Useful for commands that perform operations without returning a result.
 *
 * @param block The block to execute with the handle
 */
inline fun Jdbi.useHandle(crossinline block: (Handle) -> Unit) {
    withHandle<Unit, Exception> { block(it) }
}
