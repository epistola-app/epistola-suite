// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.stencils

import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.relocation.JsonRewrite
import app.epistola.suite.catalog.relocation.ResourceRelocator
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.StencilKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.template.model.TemplateDocument
import org.jdbi.v3.core.Handle
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/** Relocates stencils (ADR 0039): a stencil moves with its content renamed; its document is rewritten as the plan says. */
@Component
class StencilRelocator(
    private val stencils: StencilStore,
    private val objectMapper: ObjectMapper,
) : ResourceRelocator {
    override val type = StencilStore.STENCIL

    override fun move(handle: Handle, source: CatalogId, key: String, target: CatalogId, targetKey: String): Boolean = stencils.move(handle, source, key, target, targetKey)

    /** The stencil's document replaced, when it is still what the plan saw; 1 when it was, else 0. */
    override fun rewrite(handle: Handle, tenantKey: TenantKey, rewrite: JsonRewrite): Int {
        if (rewrite !is JsonRewrite.StencilContent) return 0
        val catalog = CatalogId.of(rewrite.catalogKey, TenantId(tenantKey))
        val id = StencilId(StencilKey.of(rewrite.ownerKey), catalog)
        val stencil = stencils.get(handle, id) ?: return 0
        // The plan saw the stored document, its own-catalog references relative.
        val stored = handle.createQuery("SELECT content::text FROM current_stencils WHERE tenant_key = :t AND catalog_id = ${catalogAt("t", "c")} AND id = :k")
            .bind("t", tenantKey).bindCatalog("c", catalog).bind("k", rewrite.ownerKey)
            .mapTo(String::class.java).findOne().orElse(null) ?: return 0
        if (objectMapper.readTree(stored) != objectMapper.readTree(rewrite.expected)) return 0
        val content = objectMapper.readValue(rewrite.replacement, TemplateDocument::class.java)
        // A rewrite follows a move; it is not the user's edit, so whoever edited it last stays.
        return if (stencils.write(handle, id, stencil.fields().copy(content = content), stencil.updatedBy?.value)) 1 else 0
    }
}
