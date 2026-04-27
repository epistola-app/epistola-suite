package app.epistola.suite.templates.contracts

import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode

/**
 * Checks backwards compatibility between two JSON Schema versions.
 * A new schema is backwards compatible when all data valid under the old schema
 * remains valid under the new schema.
 *
 * This is a stateless utility — instantiate directly, no Spring injection needed.
 */
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
        if (oldSchema == null && newSchema == null) {
            return CompatibilityResult(compatible = true, breakingChanges = emptyList())
        }
        if (oldSchema != null && newSchema == null) {
            return CompatibilityResult(
                compatible = false,
                breakingChanges = listOf(BreakingChange(BreakingChangeType.FIELD_REMOVED, "", "Schema removed entirely")),
            )
        }
        if (oldSchema == null) {
            return CompatibilityResult(compatible = true, breakingChanges = emptyList())
        }

        val changes = mutableListOf<BreakingChange>()
        compareProperties(oldSchema, newSchema!!, "", changes)
        return CompatibilityResult(compatible = changes.isEmpty(), breakingChanges = changes)
    }

    private fun compareProperties(oldSchema: ObjectNode, newSchema: ObjectNode, basePath: String, changes: MutableList<BreakingChange>) {
        val oldProperties = oldSchema.get("properties") as? ObjectNode
        val newProperties = newSchema.get("properties") as? ObjectNode

        val oldRequired: Set<String> = extractRequired(oldSchema)
        val newRequired: Set<String> = extractRequired(newSchema)

        if (oldProperties != null) {
            val oldFields = fieldNames(oldProperties)
            val newFields = if (newProperties != null) fieldNames(newProperties) else emptySet()

            for (fieldName in oldFields) {
                val path = if (basePath.isEmpty()) fieldName else "$basePath.$fieldName"

                if (!newFields.contains(fieldName)) {
                    changes.add(BreakingChange(BreakingChangeType.FIELD_REMOVED, path, "\"$fieldName\" removed"))
                    continue
                }

                val oldField = oldProperties.get(fieldName) as? ObjectNode ?: continue
                val newField = newProperties!!.get(fieldName) as? ObjectNode ?: continue

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

                // Check constraint narrowing
                checkConstraintNarrowing(oldField, newField, path, changes)

                // Recurse into nested objects
                val oldFieldType = oldField.get("type")?.asString()
                val newFieldType = newField.get("type")?.asString()
                if (oldFieldType == "object" && newFieldType == "object") {
                    compareProperties(oldField, newField, path, changes)
                } else if (oldFieldType == "array" && newFieldType == "array") {
                    val oldItems = oldField.get("items") as? ObjectNode
                    val newItems = newField.get("items") as? ObjectNode
                    if (oldItems != null && newItems != null) {
                        if (oldItems.get("type")?.asString() == "object" && newItems.get("type")?.asString() == "object") {
                            compareProperties(oldItems, newItems, "$path[]", changes)
                        }
                        checkConstraintNarrowing(oldItems, newItems, "$path[]", changes)
                    }
                }
            }
        }

        // Check for new required fields (not in old schema at all)
        if (newProperties != null) {
            val oldFields = if (oldProperties != null) fieldNames(oldProperties) else emptySet()
            for (fieldName in newRequired) {
                if (!oldFields.contains(fieldName)) {
                    val path = if (basePath.isEmpty()) fieldName else "$basePath.$fieldName"
                    changes.add(BreakingChange(BreakingChangeType.REQUIRED_ADDED, path, "\"$fieldName\" added as required"))
                }
            }
        }
    }

    /**
     * Detects constraint narrowing: when a new schema adds or tightens constraints
     * that would reject data previously accepted by the old schema.
     */
    private fun checkConstraintNarrowing(oldField: ObjectNode, newField: ObjectNode, path: String, changes: MutableList<BreakingChange>) {
        // Enum: values removed from allowed set
        val oldEnum = extractStringSet(oldField.get("enum"))
        val newEnum = extractStringSet(newField.get("enum"))
        if (oldEnum != null && newEnum != null) {
            val removed = oldEnum - newEnum
            if (removed.isNotEmpty()) {
                changes.add(BreakingChange(BreakingChangeType.CONSTRAINT_NARROWED, path, "enum values removed: ${removed.joinToString()}"))
            }
        } else if (oldEnum == null && newEnum != null) {
            // Adding an enum constraint where there was none
            changes.add(BreakingChange(BreakingChangeType.CONSTRAINT_NARROWED, path, "enum constraint added"))
        }

        // Numeric: minimum increased or maximum decreased
        checkNumericNarrowing(oldField, newField, "minimum", path, changes, narrowsWhenNew = { old, new -> new > old })
        checkNumericNarrowing(oldField, newField, "exclusiveMinimum", path, changes, narrowsWhenNew = { old, new -> new > old })
        checkNumericNarrowing(oldField, newField, "maximum", path, changes, narrowsWhenNew = { old, new -> new < old })
        checkNumericNarrowing(oldField, newField, "exclusiveMaximum", path, changes, narrowsWhenNew = { old, new -> new < old })

        // String: minLength increased or maxLength decreased
        checkNumericNarrowing(oldField, newField, "minLength", path, changes, narrowsWhenNew = { old, new -> new > old })
        checkNumericNarrowing(oldField, newField, "maxLength", path, changes, narrowsWhenNew = { old, new -> new < old })

        // Array: minItems increased or maxItems decreased
        checkNumericNarrowing(oldField, newField, "minItems", path, changes, narrowsWhenNew = { old, new -> new > old })
        checkNumericNarrowing(oldField, newField, "maxItems", path, changes, narrowsWhenNew = { old, new -> new < old })

        // Pattern: added or changed
        val oldPattern = oldField.get("pattern")?.asString()
        val newPattern = newField.get("pattern")?.asString()
        if (oldPattern == null && newPattern != null) {
            changes.add(BreakingChange(BreakingChangeType.CONSTRAINT_NARROWED, path, "pattern constraint added: $newPattern"))
        } else if (oldPattern != null && newPattern != null && oldPattern != newPattern) {
            changes.add(BreakingChange(BreakingChangeType.CONSTRAINT_NARROWED, path, "pattern changed from $oldPattern to $newPattern"))
        }
    }

    private fun checkNumericNarrowing(
        oldField: ObjectNode,
        newField: ObjectNode,
        keyword: String,
        path: String,
        changes: MutableList<BreakingChange>,
        narrowsWhenNew: (Double, Double) -> Boolean,
    ) {
        val oldVal = oldField.get(keyword)?.asDouble()
        val newVal = newField.get(keyword)?.asDouble()

        if (oldVal == null && newVal != null) {
            // Adding a constraint where there was none
            changes.add(BreakingChange(BreakingChangeType.CONSTRAINT_NARROWED, path, "$keyword constraint added: $newVal"))
        } else if (oldVal != null && newVal != null && narrowsWhenNew(oldVal, newVal)) {
            changes.add(BreakingChange(BreakingChangeType.CONSTRAINT_NARROWED, path, "$keyword narrowed from $oldVal to $newVal"))
        }
    }

    private fun extractRequired(schema: ObjectNode): Set<String> {
        val requiredNode = schema.get("required") ?: return emptySet()
        val result = mutableSetOf<String>()
        for (element in requiredNode) {
            result.add(element.asString())
        }
        return result
    }

    private fun fieldNames(node: ObjectNode): Set<String> {
        val result = mutableSetOf<String>()
        for ((key, _) in node.properties()) {
            result.add(key)
        }
        return result
    }

    private fun extractStringSet(node: JsonNode?): Set<String>? {
        if (node == null || !node.isArray) return null
        val result = mutableSetOf<String>()
        for (element in node) {
            result.add(element.asString())
        }
        return result
    }

    private fun effectiveType(node: JsonNode?): String? {
        if (node == null) return null
        val type = node.get("type")?.asString() ?: return null
        if (type == "array") {
            val itemType = node.get("items")?.get("type")?.asString()
            return if (itemType != null) "array<$itemType>" else "array"
        }
        return type
    }
}
