package app.epistola.suite.mediator

/**
 * Extension functions for Command and Query that enable clean, idiomatic dispatch syntax.
 *
 * Instead of injecting Mediator and calling mediator.send(command), use:
 * ```kotlin
 * val tenant = CreateTenant("name").execute()
 * val tenants = ListTenants().query()
 * ```
 *
 * These extensions rely on MediatorContext having a bound mediator,
 * which is automatically handled by MediatorFilter for HTTP requests.
 */

/**
 * Executes this command using the currently bound Mediator.
 *
 * @return the result of the command execution
 * @throws IllegalStateException if no mediator is bound to the current scope
 */
fun <R> Command<R>.execute(): R = MediatorContext.current().send(this)

/**
 * Executes this query using the currently bound Mediator.
 *
 * @return the result of the query execution
 * @throws IllegalStateException if no mediator is bound to the current scope
 */
fun <R> Query<R>.query(): R = MediatorContext.current().query(this)
