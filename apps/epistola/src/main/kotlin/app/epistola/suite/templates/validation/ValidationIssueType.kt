package app.epistola.suite.templates.validation

/**
 * Types of validation issues that can occur when comparing data against a schema.
 */
enum class ValidationIssueType {
    /** Value type doesn't match the expected schema type */
    TYPE_MISMATCH,

    /** Required field is missing from the data */
    MISSING_REQUIRED,

    /** Field exists in data but not defined in schema */
    UNKNOWN_FIELD,
}
