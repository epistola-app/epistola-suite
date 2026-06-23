package app.epistola.suite.templates.commands.variants

import app.epistola.suite.attributes.codelists.queries.CodeListEntryExists
import app.epistola.suite.attributes.model.VariantAttributeDefinition
import app.epistola.suite.attributes.queries.ListAttributeDefinitions
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.paging.PageRequest
import app.epistola.suite.mediator.query
import app.epistola.suite.validation.validate

/**
 * Validates variant attributes against the tenant's attribute definition registry.
 *
 * Each attribute key is either:
 *  - **catalog-qualified** — `"<catalogKey>.<attributeSlug>"`, e.g.
 *    `"system.locale"`. Resolves to exactly one definition. Use this form when
 *    the same slug exists in multiple catalogs of the tenant (e.g.
 *    `system.language` vs `epistola-demo.language`); the catalog prefix makes
 *    the choice explicit and removes the silent-collision footgun.
 *  - **bare slug** — `"locale"`, no dot. Resolves tenant-wide; if the slug
 *    is ambiguous (defined in multiple catalogs), `associateBy` picks one
 *    non-deterministically. Kept for backward compatibility with variants
 *    authored before catalog-qualified references existed; new callers should
 *    prefer the qualified form.
 *
 * Value validation respects the resolved definition's constraint:
 *  - free format        → any value
 *  - inline values      → value must be in `allowedValues`
 *  - bound to code list → value must exist as an entry in the bound list.
 *    Hidden entries are accepted so existing variants don't break when an
 *    entry is sunset.
 *
 * Catalog slugs match `^[a-z][a-z0-9]*(-[a-z0-9]+)*$` (no `.`), so splitting
 * on the first `.` is unambiguous.
 */
fun validateAttributes(tenantId: TenantId, attributes: Map<String, String>) {
    if (attributes.isEmpty()) return

    val definitions = ListAttributeDefinitions(tenantId, page = PageRequest.ALL).query().items
    val byQualified = definitions.associateBy { "${it.catalogKey.value}.${it.id.value}" }
    val byBareSlug = definitions.associateBy { it.id.value }

    for ((key, value) in attributes) {
        val definition = if ('.' in key) byQualified[key] else byBareSlug[key]
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
