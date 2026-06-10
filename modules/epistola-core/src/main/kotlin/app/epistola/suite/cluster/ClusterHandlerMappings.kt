package app.epistola.suite.cluster

/**
 * Builds a handler lookup map and rejects ambiguous handler registrations.
 *
 * Timer and scheduled-task dispatch keys are explicit string contracts, so two
 * Spring beans advertising the same key would make dispatch order-dependent.
 * Failing during scheduler construction is clearer than silently picking one.
 */
internal fun <T> uniqueHandlersByType(
    handlerKind: String,
    handlers: List<T>,
    typeSelector: (T) -> String,
): Map<String, T> {
    val duplicateTypes = handlers
        .groupingBy(typeSelector)
        .eachCount()
        .filterValues { it > 1 }
        .keys
        .sorted()

    check(duplicateTypes.isEmpty()) {
        "Duplicate $handlerKind registered for type(s): ${duplicateTypes.joinToString(", ")}"
    }

    return handlers.associateBy(typeSelector)
}
