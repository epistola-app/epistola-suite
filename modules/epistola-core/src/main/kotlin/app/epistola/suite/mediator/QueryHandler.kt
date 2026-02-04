package app.epistola.suite.mediator

/**
 * Handler interface for processing queries.
 *
 * @param Q the query type
 * @param R the result type
 */
interface QueryHandler<Q : Query<R>, R> {
    fun handle(query: Q): R
}
