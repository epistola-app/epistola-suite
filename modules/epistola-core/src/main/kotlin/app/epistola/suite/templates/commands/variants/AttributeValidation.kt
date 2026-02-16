package app.epistola.suite.templates.commands.variants

import app.epistola.suite.attributes.queries.ListAttributeDefinitions
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.mediator.query
import app.epistola.suite.validation.validate

/**
 * Validates variant attributes against the tenant's attribute definition registry.
 *
 * Checks that:
 * 1. All attribute keys exist as defined attributes for the tenant
 * 2. All attribute values are in the allowed values list (if the definition has restricted values)
 */
fun validateAttributes(tenantId: TenantId, attributes: Map<String, String>) {
    if (attributes.isEmpty()) return

    val definitions = ListAttributeDefinitions(tenantId).query()
    val definitionMap = definitions.associateBy { it.id.value }

    for ((key, value) in attributes) {
        val definition = definitionMap[key]
        validate("attributes", definition != null) { "Unknown attribute '$key'. Define it in the attribute registry first." }
        if (definition!!.allowedValues.isNotEmpty()) {
            validate("attributes", value in definition.allowedValues) {
                "Invalid value '$value' for attribute '$key'. Allowed values: ${definition.allowedValues.joinToString(", ")}"
            }
        }
    }
}
