package app.epistola.suite.templates.validation

import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode

/**
 * Checks backwards compatibility between two JSON Schema versions.
 * A new schema is backwards compatible when all data valid under the old schema
 * remains valid under the new schema.
 */
@Component
class SchemaCompatibilityChecker {

    data class CompatibilityResult(
        val compatible: Boolean,
        val breakingChanges: List<BreakingChange>,
    )

    data class BreakingChange(
        val type: BreakingChangeType,
        val path: String,
        val description: String,
    )

    enum class BreakingChangeType {
        FIELD_REMOVED,
        TYPE_CHANGED,
        REQUIRED_ADDED,
        MADE_REQUIRED,
        CONSTRAINT_NARROWED,
    }

    fun checkCompatibility(oldSchema: ObjectNode?, newSchema: ObjectNode?): CompatibilityResult {
        // Both null = compatible (no contract)
        if (oldSchema == null && newSchema == null) {
            return CompatibilityResult(compatible = true, breakingChanges = emptyList())
        }
        // Old had schema, new doesn't = removing contract is breaking
        if (oldSchema != null && newSchema == null) {
            return CompatibilityResult(
                compatible = false,
                breakingChanges = listOf(BreakingChange(BreakingChangeType.FIELD_REMOVED, "", "Schema removed entirely")),
            )
        }
        // Old had no schema, new adds one = compatible (no existing data to break)
        if (oldSchema == null && newSchema != null) {
            return CompatibilityResult(compatible = true, breakingChanges = emptyList())
        }

        val changes = mutableListOf<BreakingChange>()
        compareProperties(oldSchema!!, newSchema!!, "", changes)
        return CompatibilityResult(compatible = changes.isEmpty(), breakingChanges = changes)
    }

    private fun compareProperties(oldSchema: ObjectNode, newSchema: ObjectNode, basePath: String, changes: MutableList<BreakingChange>) {
        val oldProperties = oldSchema.get("properties") as? ObjectNode
        val newProperties = newSchema.get("properties") as? ObjectNode

        val oldRequired: Set<String> = extractRequired(oldSchema)
        val newRequired: Set<String> = extractRequired(newSchema)

        // Check for removed fields and type changes
        if (oldProperties != null) {
            val oldFields = oldProperties.properties().asSequence().map { it.key }.toSet()
            val newFields = newProperties?.properties()?.asSequence()?.map { it.key }?.toSet() ?: emptySet()

            for (fieldName in oldFields) {
                val path = if (basePath.isEmpty()) fieldName else "$basePath.$fieldName"

                if (!newFields.contains(fieldName)) {
                    changes.add(BreakingChange(BreakingChangeType.FIELD_REMOVED, path, "\"$fieldName\" removed"))
                    continue
                }

                val oldField = oldProperties.get(fieldName)
                val newField = newProperties!!.get(fieldName)

                // Check type changes
                val oldType = effectiveType(oldField)
                val newType = effectiveType(newField)
                if (oldType != null && newType != null && oldType != newType) {
                    changes.add(BreakingChange(BreakingChangeType.TYPE_CHANGED, path, "\"$fieldName\" type changed from $oldType to $newType"))
                }

                // Check optional → required
                if (!oldRequired.contains(fieldName) && newRequired.contains(fieldName)) {
                    changes.add(BreakingChange(BreakingChangeType.MADE_REQUIRED, path, "\"$fieldName\" is now required"))
                }

                // Recurse into nested objects
                if (oldField is ObjectNode && newField is ObjectNode) {
                    val oldFieldType = oldField.get("type")?.asText()
                    val newFieldType = newField.get("type")?.asText()
                    if (oldFieldType == "object" && newFieldType == "object") {
                        compareProperties(oldField, newField, path, changes)
                    } else if (oldFieldType == "array" && newFieldType == "array") {
                        val oldItems = oldField.get("items") as? ObjectNode
                        val newItems = newField.get("items") as? ObjectNode
                        if (oldItems != null && newItems != null && oldItems.get("type")?.asText() == "object" && newItems.get("type")?.asText() == "object") {
                            compareProperties(oldItems, newItems, "$path[]", changes)
                        }
                    }
                }
            }
        }

        // Check for new required fields (not in old schema at all)
        if (newProperties != null) {
            val oldFields = oldProperties?.properties()?.asSequence()?.map { it.key }?.toSet() ?: emptySet()
            for (fieldName in newRequired) {
                if (!oldFields.contains(fieldName)) {
                    val path = if (basePath.isEmpty()) fieldName else "$basePath.$fieldName"
                    changes.add(BreakingChange(BreakingChangeType.REQUIRED_ADDED, path, "\"$fieldName\" added as required"))
                }
            }
        }
    }

    private fun extractRequired(schema: ObjectNode): Set<String> {
        val requiredNode = schema.get("required") ?: return emptySet()
        val result = mutableSetOf<String>()
        for (element in requiredNode) {
            result.add(element.asText())
        }
        return result
    }

    private fun effectiveType(node: JsonNode?): String? {
        if (node == null) return null
        val type = node.get("type")?.asText() ?: return null
        if (type == "array") {
            val itemType = node.get("items")?.get("type")?.asText()
            return if (itemType != null) "array<$itemType>" else "array"
        }
        return type
    }
}
