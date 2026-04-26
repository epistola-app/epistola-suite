package app.epistola.suite.templates.analysis

import org.springframework.stereotype.Component
import tools.jackson.databind.node.ObjectNode

/**
 * Checks whether a specific template version is compatible with a new contract schema.
 *
 * Unlike [SchemaCompatibilityChecker][app.epistola.suite.templates.validation.SchemaCompatibilityChecker]
 * which compares two schemas structurally, this service answers the question:
 * "Can this specific template still render with the new schema?"
 *
 * It uses the template's `referencedPaths` (extracted from expressions) and compares
 * each path's type in the old schema vs the new schema.
 */
@Component
class TemplateContractCompatibilityService(
    private val navigator: SchemaPathNavigator,
) {

    /**
     * Checks if a template version's referenced paths are compatible with a new schema.
     *
     * @param referencedPaths Paths extracted from the template's expressions
     * @param oldSchema The schema the template was built against (from contract_version FK)
     * @param newSchema The new schema being published
     * @return Compatibility result with any incompatibilities
     */
    fun checkCompatibility(
        referencedPaths: Set<String>,
        oldSchema: ObjectNode?,
        newSchema: ObjectNode?,
    ): TemplateCompatibilityResult {
        if (referencedPaths.isEmpty()) {
            return TemplateCompatibilityResult(compatible = true, incompatibilities = emptyList())
        }

        // Schema removed entirely — all referenced fields are gone
        if (oldSchema != null && newSchema == null) {
            return TemplateCompatibilityResult(
                compatible = false,
                incompatibilities = referencedPaths.map {
                    FieldIncompatibility(path = it, reason = IncompatibilityReason.FIELD_REMOVED, description = "Schema removed entirely")
                },
            )
        }

        // No schema before or after — nothing to check
        if (newSchema == null) {
            return TemplateCompatibilityResult(compatible = true, incompatibilities = emptyList())
        }

        val incompatibilities = mutableListOf<FieldIncompatibility>()

        for (path in referencedPaths) {
            val newField = navigator.resolve(newSchema, path)

            if (!newField.found) {
                incompatibilities.add(
                    FieldIncompatibility(
                        path = path,
                        reason = IncompatibilityReason.FIELD_REMOVED,
                        description = "\"$path\" not found in new schema",
                    ),
                )
                continue
            }

            // Check type change (only if old schema exists to compare against)
            if (oldSchema != null) {
                val oldField = navigator.resolve(oldSchema, path)
                if (oldField.found && oldField.type != newField.type) {
                    incompatibilities.add(
                        FieldIncompatibility(
                            path = path,
                            reason = IncompatibilityReason.TYPE_CHANGED,
                            description = "\"$path\" type changed from ${oldField.type} to ${newField.type}",
                        ),
                    )
                }
            }
        }

        return TemplateCompatibilityResult(
            compatible = incompatibilities.isEmpty(),
            incompatibilities = incompatibilities,
        )
    }
}

data class TemplateCompatibilityResult(
    val compatible: Boolean,
    val incompatibilities: List<FieldIncompatibility>,
)

data class FieldIncompatibility(
    val path: String,
    val reason: IncompatibilityReason,
    val description: String,
)

enum class IncompatibilityReason {
    FIELD_REMOVED,
    TYPE_CHANGED,
}
