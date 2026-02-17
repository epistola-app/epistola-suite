package app.epistola.suite.validation

import org.jdbi.v3.core.statement.UnableToExecuteStatementException
import java.sql.SQLException

/**
 * Executes the given block and converts PostgreSQL unique constraint violations (sqlState 23505)
 * into [DuplicateIdException]. Other exceptions are re-thrown as-is.
 *
 * @param entityType The type of entity being created (e.g., "environment", "theme")
 * @param id The ID being inserted
 * @param block The block that performs the insert
 * @return The result of the block
 * @throws DuplicateIdException if a unique constraint violation occurs
 */
inline fun <T> executeOrThrowDuplicate(entityType: String, id: String, block: () -> T): T = try {
    block()
} catch (e: UnableToExecuteStatementException) {
    if (isUniqueViolation(e)) {
        throw DuplicateIdException(entityType, id)
    }
    throw e
}

/**
 * Checks whether the given exception is caused by a PostgreSQL unique constraint violation.
 * SQL state 23505 = unique_violation.
 */
fun isUniqueViolation(e: UnableToExecuteStatementException): Boolean {
    var cause: Throwable? = e.cause
    while (cause != null) {
        if (cause is SQLException && cause.sqlState == "23505") {
            return true
        }
        cause = cause.cause
    }
    return false
}
