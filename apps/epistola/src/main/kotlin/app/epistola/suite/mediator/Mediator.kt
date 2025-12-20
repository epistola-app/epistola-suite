package app.epistola.suite.mediator

/**
 * Central gateway for dispatching commands and queries to their handlers.
 * Provides a unified interface for executing operations without direct handler dependencies.
 */
interface Mediator {
    /**
     * Sends a command to its handler and returns the result.
     *
     * @param command the command to execute
     * @return the result of the command execution
     * @throws IllegalArgumentException if no handler is found for the command
     */
    fun <R> send(command: Command<R>): R

    /**
     * Executes a query and returns the result.
     *
     * @param query the query to execute
     * @return the result of the query execution
     * @throws IllegalArgumentException if no handler is found for the query
     */
    fun <R> query(query: Query<R>): R
}
