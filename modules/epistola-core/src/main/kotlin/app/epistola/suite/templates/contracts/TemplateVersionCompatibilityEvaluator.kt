package app.epistola.suite.templates.contracts

import app.epistola.suite.templates.analysis.FieldIncompatibility
import app.epistola.suite.templates.analysis.IncompatibilityReason
import app.epistola.suite.templates.analysis.TemplateCompatibilityResult
import tools.jackson.databind.node.ObjectNode

/**
 * Pure comparison of a template version's referenced field paths against an old and a
 * new contract schema. Reports FIELD_REMOVED (path not found in the new schema) and
 * TYPE_CHANGED (path exists but its type differs from the old schema's).
 *
 * Shared by [app.epistola.suite.templates.contracts.queries.CheckTemplateVersionCompatibility]
 * (single version) and [app.epistola.suite.templates.contracts.queries.CheckContractPublishImpact]
 * (all affected versions in one pass — no per-version query fan-out).
 */
class TemplateVersionCompatibilityEvaluator {

    private val navigator = SchemaPathNavigator()

    fun evaluate(
        referencedPaths: Set<String>,
        oldSchema: ObjectNode?,
        newSchema: ObjectNode?,
    ): TemplateCompatibilityResult {
        if (referencedPaths.isEmpty()) {
            return TemplateCompatibilityResult(compatible = true, incompatibilities = emptyList())
        }

        // Schema removed entirely
        if (oldSchema != null && newSchema == null) {
            return TemplateCompatibilityResult(
                compatible = false,
                incompatibilities = referencedPaths.map {
                    FieldIncompatibility(it, IncompatibilityReason.FIELD_REMOVED, "Schema removed entirely")
                },
            )
        }

        if (newSchema == null) {
            return TemplateCompatibilityResult(compatible = true, incompatibilities = emptyList())
        }

        val incompatibilities = mutableListOf<FieldIncompatibility>()
        for (path in referencedPaths) {
            val newField = navigator.resolve(newSchema, path)

            if (!newField.found) {
                incompatibilities.add(
                    FieldIncompatibility(path, IncompatibilityReason.FIELD_REMOVED, "\"$path\" not found in new schema"),
                )
                continue
            }

            if (oldSchema != null) {
                val oldField = navigator.resolve(oldSchema, path)
                if (oldField.found && oldField.type != newField.type) {
                    incompatibilities.add(
                        FieldIncompatibility(
                            path,
                            IncompatibilityReason.TYPE_CHANGED,
                            "\"$path\" type changed from ${oldField.type} to ${newField.type}",
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
