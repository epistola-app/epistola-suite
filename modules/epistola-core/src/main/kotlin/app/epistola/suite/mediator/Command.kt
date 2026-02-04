package app.epistola.suite.mediator

/**
 * Marker interface for commands that represent state-changing operations.
 * The type parameter R represents the return type of the command handler.
 */
interface Command<out R>
