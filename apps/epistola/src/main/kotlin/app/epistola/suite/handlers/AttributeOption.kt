package app.epistola.suite.handlers

import app.epistola.suite.attributes.codelists.queries.ListCodeListEntries
import app.epistola.suite.attributes.model.VariantAttributeDefinition
import app.epistola.suite.mediator.query

/**
 * A single option offered for an attribute value in a variant form dropdown.
 *
 * The `code` is what gets persisted on the variant; `label` is what's shown in
 * the UI. For inline allowed-values, code and label are identical; for
 * code-list bindings, code/label come straight from `code_list_entries`.
 */
data class AttributeOption(
    val code: String,
    val label: String,
)

/**
 * Builds the dropdown options for each attribute definition shown on a variant
 * form. Returns a map keyed by the attribute's slug so Thymeleaf can look up
 * `attributeOptions[attrDef.id]` without invoking a method per row.
 *
 * Options are sourced from one of:
 *  - inline `allowedValues` (free format yields an empty list — the dropdown
 *    won't render any options and the UI falls back to "- Not set -" only),
 *  - code list entries, filtered to visible entries only (hidden ones still
 *    pass validation, but we don't surface them in pickers).
 */
fun buildAttributeOptions(
    definitions: List<VariantAttributeDefinition>,
): Map<String, List<AttributeOption>> = definitions.associate { def ->
    def.id.value to optionsFor(def)
}

private fun optionsFor(def: VariantAttributeDefinition): List<AttributeOption> {
    val codeListId = def.codeListId
    if (codeListId != null) {
        return ListCodeListEntries(codeListId, includeHidden = false).query()
            .map { AttributeOption(code = it.code, label = it.label) }
    }
    return def.allowedValues.map { AttributeOption(code = it, label = it) }
}
