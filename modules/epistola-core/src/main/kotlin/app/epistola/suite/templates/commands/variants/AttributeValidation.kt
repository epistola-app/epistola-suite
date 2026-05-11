package app.epistola.suite.templates.commands.variants

import app.epistola.suite.attributes.codelists.queries.CodeListEntryExists
import app.epistola.suite.attributes.model.VariantAttributeDefinition
import app.epistola.suite.attributes.queries.ListAttributeDefinitions
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.mediator.query
import app.epistola.suite.validation.validate

/**
 * Validates variant attributes against the tenant's attribute definition registry.
 *
 * Checks that:
 * 1. All attribute keys exist as defined attributes for the tenant
 * 2. All attribute values respect the definition's constraint:
 *    - free format        → any value
 *    - inline values      → value must be in `allowedValues`
 *    - bound to code list → value must exist as an entry in the bound list.
 *      Hidden entries are accepted so existing variants don't break when an
 *      entry is sunset.
 *
 * Note: this iteration looks up attribute definitions by slug only (tenant-wide).
 * If the same slug exists in multiple catalogs of the tenant, `associateBy`
 * silently picks one — a known limitation that goes away when variant attribute
 * references become catalog-qualified (see CHANGELOG "Code lists — future work").
 */
fun validateAttributes(tenantId: TenantId, attributes: Map<String, String>) {
    if (attributes.isEmpty()) return

    val definitions = ListAttributeDefinitions(tenantId).query()
    val definitionMap = definitions.associateBy { it.id.value }

    for ((key, value) in attributes) {
        val definition = definitionMap[key]
        validate("attributes", definition != null) { "Unknown attribute '$key'. Define it in the attribute registry first." }
        validateValue(key, value, definition!!)
    }
}

private fun validateValue(key: String, value: String, definition: VariantAttributeDefinition) {
    val codeListId = definition.codeListId
    if (codeListId != null) {
        val exists = CodeListEntryExists(
            tenantKey = codeListId.tenantKey,
            catalogKey = codeListId.catalogKey,
            codeListSlug = codeListId.key,
            code = value,
        ).query()
        validate("attributes", exists) {
            "Invalid value '$value' for attribute '$key'. Not a member of code list '${codeListId.catalogKey.value}/${codeListId.key.value}'."
        }
        return
    }
    if (definition.allowedValues.isNotEmpty()) {
        validate("attributes", value in definition.allowedValues) {
            "Invalid value '$value' for attribute '$key'. Allowed values: ${definition.allowedValues.joinToString(", ")}"
        }
    }
}
