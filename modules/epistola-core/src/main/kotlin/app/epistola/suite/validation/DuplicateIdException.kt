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

/**
 * User-facing "already exists" copy for a form error slot: article-correct and without
 * the raw id (unlike [DuplicateIdException.message]). The single source for this message,
 * shared by the form binder's `executeOrFormError` and the dialog handlers.
 */
val DuplicateIdException.formMessage: String
    get() {
        val article = if (entityType.firstOrNull() in setOf('a', 'e', 'i', 'o', 'u')) "An" else "A"
        return "$article $entityType with this ID already exists"
    }
