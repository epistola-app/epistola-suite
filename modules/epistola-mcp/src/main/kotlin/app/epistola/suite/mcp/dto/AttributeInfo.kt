// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.mcp.dto

import app.epistola.suite.attributes.model.VariantAttributeDefinition
import java.time.OffsetDateTime

/**
 * Attribute definition exposed to AI clients. Mirrors the REST `AttributeDto`
 * shape but stays inside the MCP module's read-only world view.
 *
 * Constraint kind:
 *  - Free format: `allowedValues` empty and `codeListBinding` null.
 *  - Inline values: `allowedValues` non-empty.
 *  - Bound to a code list: `codeListBinding` set; `allowedValues` empty.
 *
 * `editable` says whether its catalog can be changed here; `readOnly` is its
 * opposite (e.g. the bundled `system` catalog's reserved `locale`/`language`/`country`).
 */
data class AttributeInfo(
    val key: String,
    val catalog: String,
    val displayName: String,
    val allowedValues: List<String>,
    val codeListBinding: CodeListBindingInfo?,
    val editable: Boolean,
    val readOnly: Boolean,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
) {
    companion object {
        fun from(def: VariantAttributeDefinition, editable: Boolean): AttributeInfo = AttributeInfo(
            key = def.id.value,
            catalog = def.catalogKey.value,
            displayName = def.displayName,
            allowedValues = def.allowedValues,
            codeListBinding = def.codeListId?.let { id ->
                CodeListBindingInfo(catalog = id.catalogKey.value, slug = id.key.value)
            },
            editable = editable,
            readOnly = !editable,
            createdAt = def.createdAt,
            updatedAt = def.updatedAt,
        )
    }
}

data class CodeListBindingInfo(
    val catalog: String,
    val slug: String,
)
