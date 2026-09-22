// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.relocation

import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.graph.CatalogResourceType
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.common.ids.VersionId
import app.epistola.suite.common.ids.VersionKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.commands.UpdateDocumentTemplate
import app.epistola.suite.templates.queries.versions.GetVersion
import app.epistola.suite.themes.commands.CreateTheme
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A published version freezes its theme into `resolved_theme`. A font that theme names without a
 * catalog resolves, at render, against the catalog of the template's bound theme -- which follows
 * the theme when it moves. The theme lives in `letters` with font `acme`, bound to a template in
 * `archive`, so moving the theme to `shared` creates no catalog cycle.
 */
class ThemeSnapshotRelocationTest : RelocationTestSupport() {

    @Test
    fun `a publish freezes the catalog a theme's relative font resolves against`() {
        val tenant = boundThemeWithRelativeFont("Publish freezes font catalog")
        val version = publishBound(tenant)

        val snapshot = withMediator { GetVersion(VersionId(version, templateVariant(tenant, archive))).query()!!.resolvedTheme!! }
        assertThat(snapshot.documentStyles["fontFamily"]).isEqualTo(mapOf("slug" to "acme", "catalogKey" to letters.value))
        assertThat(snapshot.fontFingerprints).containsOnlyKeys("letters/acme")
    }

    @Test
    fun `a published document still renders after its bound theme with a relative font moves`() {
        val tenant = boundThemeWithRelativeFont("Render after relative theme move")
        val version = publishBound(tenant)
        assertPreviewRenders(tenant, archive, version)

        move(tenant, address(CatalogResourceType.THEME, letters, "brand").movedTo(shared))

        assertPreviewRenders(tenant, archive, version)
    }

    /**
     * Versions published before a publish froze the font's catalog hold it relatively. When their
     * theme moves, the move pins those snapshots to the catalog the font resolves against today --
     * bytes change, meaning does not -- so they keep rendering.
     */
    @Test
    fun `a version published with a relative theme font keeps rendering after the theme moves`() {
        val tenant = boundThemeWithRelativeFont("Legacy snapshot after theme move")
        val version = publishBound(tenant)
        unqualifySnapshotFont(tenant, archive, version, letters.value, "acme")
        assertPreviewRenders(tenant, archive, version)

        val plan = move(tenant, address(CatalogResourceType.THEME, letters, "brand").movedTo(shared))

        assertThat(plan.mutableRewriteCount).describedAs("the theme row and the published snapshot").isEqualTo(2)
        assertPreviewRenders(tenant, archive, version)
    }

    /** Font `letters/acme`, and theme `letters/brand` naming it without a catalog. */
    private fun boundThemeWithRelativeFont(name: String): TenantKey {
        val tenant = tenantWith(name, listOf(letters, shared, archive))
        importFont(tenant, letters, "acme")
        withMediator { CreateTheme(ThemeId(ThemeKey.of("brand"), catalogId(tenant, letters)), "Brand", documentStyles = fontStyle("acme", catalog = null)).execute() }
        return tenant
    }

    /** Publishes `archive/invoice`, bound to theme `letters/brand`. */
    private fun publishBound(tenant: TenantKey): VersionKey = publishTemplate(tenant, archive, textModel()) { template ->
        UpdateDocumentTemplate(id = template, themeId = ThemeKey.of("brand"), themeCatalogKey = letters).execute()
    }

    /**
     * Puts a published snapshot back in the shape versions had before a publish froze the font's
     * catalog. No command writes that shape any more, so the row is edited directly.
     */
    private fun unqualifySnapshotFont(tenant: TenantKey, catalog: CatalogKey, version: VersionKey, fontCatalog: String, slug: String) {
        val variant = templateVariant(tenant, catalog)
        jdbi.useHandle<Exception> { handle ->
            val changed = handle.createUpdate(
                """
                UPDATE template_versions
                SET resolved_theme = jsonb_set(
                        (resolved_theme #- '{documentStyles,fontFamily,catalogKey}') #- ARRAY['fontFingerprints', :qualified],
                        ARRAY['fontFingerprints', :relative],
                        resolved_theme -> 'fontFingerprints' -> :qualified)
                WHERE tenant_key = :tenantKey AND variant_key = :variantKey AND id = :version
                  AND template_resource_id = (SELECT resource_id FROM document_templates
                                              WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey AND id = :templateKey)
                """,
            )
                .bind("qualified", "$fontCatalog/$slug")
                .bind("relative", "/$slug")
                .bind("tenantKey", tenant)
                .bind("catalogKey", catalog)
                .bind("templateKey", variant.templateId.key)
                .bind("variantKey", variant.key)
                .bind("version", version)
                .execute()
            assertThat(changed).isEqualTo(1)
        }
    }
}
