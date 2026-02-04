package app.epistola.suite.templates.validation

/**
 * Exception thrown when data examples fail validation against a JSON Schema.
 *
 * @property validationErrors Map of example name to list of validation errors
 */
class DataModelValidationException(
    val validationErrors: Map<String, List<ValidationError>>,
) : RuntimeException("Data examples failed validation against schema")
