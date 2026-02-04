package app.epistola.suite.templates.validation

/**
 * Result of analyzing schema compatibility with existing data examples.
 *
 * @property compatible True if all examples are compatible with the new schema
 * @property errors List of validation errors that cannot be auto-migrated
 * @property migrations List of suggested migrations for incompatible examples
 */
data class SchemaCompatibilityResult(
    val compatible: Boolean,
    val errors: List<ValidationError>,
    val migrations: List<MigrationSuggestion>,
) {
    companion object {
        /** Creates a result indicating full compatibility */
        fun compatible() = SchemaCompatibilityResult(
            compatible = true,
            errors = emptyList(),
            migrations = emptyList(),
        )
    }
}
