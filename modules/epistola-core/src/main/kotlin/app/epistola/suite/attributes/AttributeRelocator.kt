// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.attributes

import app.epistola.catalog.protocol.AttributeResource
import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.relocation.JsonRewrite
import app.epistola.suite.catalog.relocation.ResourceRelocator
import app.epistola.suite.common.ids.AttributeId
import app.epistola.suite.common.ids.AttributeKey
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import org.jdbi.v3.core.Handle
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/** Relocates variant attributes (ADR 0039): an attribute moves with its content renamed; its binding is rewritten as the plan says. */
@Component
class AttributeRelocator(
    private val attributes: AttributeStore,
    private val objectMapper: ObjectMapper,
) : ResourceRelocator {
    override val type = AttributeStore.ATTRIBUTE

    override fun move(handle: Handle, source: CatalogId, key: String, target: CatalogId, targetKey: String): Boolean = attributes.move(handle, source, key, target, targetKey)

    /** The attribute's binding replaced, when its content is still what the plan saw; 1 when it was, else 0. */
    override fun rewrite(handle: Handle, tenantKey: TenantKey, rewrite: JsonRewrite): Int {
        if (rewrite !is JsonRewrite.AttributeContent) return 0
        val catalog = CatalogId.of(rewrite.catalogKey, TenantId(tenantKey))
        val id = AttributeId(AttributeKey.of(rewrite.ownerKey), catalog)
        val (stored, updatedBy) = handle.createQuery(
            "SELECT (payload -> 'resource')::text AS resource, updated_by FROM current_attributes WHERE tenant_key = :t AND catalog_id = ${catalogAt("t", "c")} AND id = :k",
        ).bind("t", tenantKey).bindCatalog("c", catalog).bind("k", rewrite.ownerKey)
            .map { rs, _ -> rs.getString("resource") to rs.getObject("updated_by", UUID::class.java) }
            .findOne().orElse(null) ?: return 0
        if (objectMapper.readTree(stored) != objectMapper.readTree(rewrite.expected)) return 0
        val replacement = objectMapper.readValue(rewrite.replacement, AttributeResource::class.java)
        val fields = AttributeFields(replacement.name, replacement.allowedValues, replacement.codeListBinding)
        // A rewrite follows a move; it is not the user's edit, so whoever edited it last stays.
        return if (attributes.write(handle, id, fields, updatedBy)) 1 else 0
    }
}
