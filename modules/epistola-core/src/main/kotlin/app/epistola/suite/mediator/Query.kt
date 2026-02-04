package app.epistola.suite.mediator

/**
 * Marker interface for queries that represent read-only operations.
 * The type parameter R represents the return type of the query handler.
 */
interface Query<out R>
