package app.epistola.suite.cluster

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
