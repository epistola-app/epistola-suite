package app.epistola.suite.validation

/**
 * Exception thrown when attempting to create an entity with an ID that already exists.
 *
 * @property entityType The type of entity (e.g., "environment", "theme")
 * @property id The duplicate ID value
 */
class DuplicateIdException(
    val entityType: String,
    val id: String,
) : RuntimeException("A $entityType with ID '$id' already exists")
