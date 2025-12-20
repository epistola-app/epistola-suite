package app.epistola.suite.mediator

/**
 * Handler interface for processing commands.
 *
 * @param C the command type
 * @param R the result type
 */
interface CommandHandler<C : Command<R>, R> {
    fun handle(command: C): R
}
