// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.themes

import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.relocation.JsonRewrite
import app.epistola.suite.catalog.relocation.ResourceRelocator
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.templates.model.DocumentStyles
import org.jdbi.v3.core.Handle
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/** Relocates themes (ADR 0039): a theme moves with its content renamed; its styles are rewritten as the plan says. */
@Component
class ThemeRelocator(
    private val themes: ThemeStore,
    private val objectMapper: ObjectMapper,
) : ResourceRelocator {
    override val type = ThemeStore.THEME

    override fun move(handle: Handle, source: CatalogId, key: String, target: CatalogId, targetKey: String): Boolean = themes.move(handle, source, key, target, targetKey)

    /** The theme's styles replaced, when they are still what the plan saw; 1 when they were, else 0. */
    override fun rewrite(handle: Handle, tenantKey: TenantKey, rewrite: JsonRewrite): Int {
        if (rewrite !is JsonRewrite.ThemeStyles) return 0
        val catalog = CatalogId.of(rewrite.catalogKey, TenantId(tenantKey))
        val unchanged = handle.createQuery(
            """
            SELECT count(*) FROM current_themes
            WHERE tenant_key = :tenantKey AND catalog_id = ${catalogAt("tenantKey", "catalogKey")} AND id = :themeKey
              AND document_styles = :expectedDocumentStyles::jsonb
              AND block_style_presets IS NOT DISTINCT FROM :expectedPresets::jsonb
            """,
        )
            .bind("tenantKey", tenantKey)
            .bindCatalog("catalogKey", catalog)
            .bind("themeKey", rewrite.ownerKey)
            .bind("expectedDocumentStyles", rewrite.expectedDocumentStyles)
            .bind("expectedPresets", rewrite.expectedPresets)
            .mapTo(Long::class.java)
            .one() > 0
        if (!unchanged) return 0
        val theme = ThemeReads.get(handle, objectMapper, catalog, ThemeKey.of(rewrite.ownerKey)) ?: return 0
        val fields = theme.fields().copy(
            documentStyles = objectMapper.readValue(rewrite.replacementDocumentStyles, DocumentStyles::class.java),
            blockStylePresets = rewrite.replacementPresets?.let { objectMapper.readValue(it, BlockStylePresets::class.java) },
        )
        // A rewrite follows a move; it is not the user's edit, so whoever edited it last stays.
        return if (themes.save(handle, theme, fields, theme.updatedBy?.value) != null) 1 else 0
    }
}
