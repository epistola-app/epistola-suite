package app.epistola.suite.templates.validation

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonValue
import tools.jackson.databind.JsonNode

enum class ExpectedType(@get:JsonValue val value: String) {
    STRING("string"),
    NUMBER("number"),
    INTEGER("integer"),
    BOOLEAN("boolean"),
    DATE("date"),
    ARRAY("array"),
    OBJECT("object"),
    UNKNOWN("unknown"),
    ;

    companion object {
        @JsonCreator
        @JvmStatic
        fun fromValue(value: String?): ExpectedType = when (value?.lowercase()) {
            STRING.value -> STRING
            NUMBER.value -> NUMBER
            INTEGER.value -> INTEGER
            BOOLEAN.value -> BOOLEAN
            DATE.value -> DATE
            ARRAY.value -> ARRAY
            OBJECT.value -> OBJECT
            else -> UNKNOWN
        }
    }
}

/**
 * A suggestion for migrating a data example to be compatible with a new schema.
 *
 * @property exampleId The unique identifier of the affected example
 * @property exampleName The display name of the affected example
 * @property path JSON path to the problematic field (e.g., "$.user.age")
 * @property issue The type of validation issue detected
 * @property currentValue The current value at the path (null if missing)
 * @property expectedType The type expected by the new schema
 * @property suggestedValue The suggested migrated value (null if not auto-migratable)
 * @property autoMigratable Whether this issue can be automatically fixed
 */
data class MigrationSuggestion(
    val exampleId: String,
    val exampleName: String,
    val path: String,
    val issue: ValidationIssueType,
    val currentValue: JsonNode?,
    val expectedType: ExpectedType,
    val suggestedValue: JsonNode?,
    val autoMigratable: Boolean,
)
