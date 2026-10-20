// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.themes

import app.epistola.catalog.protocol.ThemeResource
import app.epistola.generation.pdf.SpacingScale
import app.epistola.suite.catalog.ProtocolMapper
import app.epistola.suite.catalog.dependencies.DraftDependencyView
import app.epistola.suite.catalog.inCurrentVersion
import app.epistola.suite.catalog.versionAt
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.time.EpistolaClock
import app.epistola.template.model.DocumentStyles
import app.epistola.template.model.PageSettings
import app.epistola.template.model.TemplateDocument
import app.epistola.template.model.ThemeRefOverride
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Resolved styling information combining theme and template settings.
 */
data class ResolvedStyles(
    val documentStyles: DocumentStyles,
    val pageSettings: PageSettings?,
    val blockStylePresets: BlockStylePresets,
    val spacingUnit: Float = SpacingScale.DEFAULT_BASE_UNIT,
)

/**
 * Resolves styles by merging theme and template settings according to the style cascade:
 *
 * 1. Theme document styles (lowest priority)
 * 2. Template document styles (override theme)
 * 3. Theme block preset (when block has stylePreset)
 * 4. Block inline styles (highest priority)
 *
 * Theme selection cascade:
 * 1. Variant-level theme (TemplateDocument.themeRef override) - highest priority
 * 2. Template-level default theme (DocumentTemplate.themeId) - fallback
 * 3. Tenant default theme (Tenant.defaultThemeId) - ultimate fallback
 */
@Service
class ThemeStyleResolver(
    private val jdbi: Jdbi,
    private val protocolMapper: ProtocolMapper,
) {
    /**
     * Resolves document-level styles by merging theme styles with template styles.
     * Uses the variant-level theme from TemplateDocument's themeRef if set, otherwise no theme.
     *
     * @param tenantId The tenant ID for theme lookup
     * @param templateModel The template document containing themeRef and template-level styles
     * @return Resolved styles combining theme and template settings
     */
    fun resolveStyles(tenantId: TenantKey, templateModel: TemplateDocument): ResolvedStyles = resolveStyles(tenantId, templateDefaultThemeId = null, catalogDefaultThemeId = null, templateModel = templateModel)

    /**
     * Resolves document-level styles with support for template-level and tenant-level default themes.
     * Variant-level theme (in TemplateDocument's themeRef) overrides template-level default theme,
     * which overrides tenant-level default theme.
     *
     * @param tenantId The tenant ID for theme lookup
     * @param templateDefaultThemeId The default theme from DocumentTemplate (may be null)
     * @param templateModel The template document containing optional themeRef override and styles
     * @return Resolved styles combining theme and template settings
     */
    fun resolveStyles(
        tenantId: TenantKey,
        templateDefaultThemeId: ThemeKey?,
        templateModel: TemplateDocument,
    ): ResolvedStyles = resolveStyles(tenantId, templateDefaultThemeId, catalogDefaultThemeId = null, templateModel)

    /**
     * Resolves document-level styles with full theme cascade support.
     *
     * Theme cascade order:
     * 1. Variant-level theme (TemplateDocument.themeRef override) - highest priority
     * 2. Template-level default theme (templateDefaultThemeId) - fallback
     * 3. Tenant default theme (catalogDefaultThemeId) - ultimate fallback
     *
     * @param tenantId The tenant ID for theme lookup
     * @param templateDefaultThemeId The default theme from DocumentTemplate (may be null)
     * @param catalogDefaultThemeId The default theme from Tenant (may be null)
     * @param templateModel The template document containing optional themeRef override and styles
     * @return Resolved styles combining theme and template settings
     */
    fun resolveStyles(
        tenantId: TenantKey,
        templateDefaultThemeId: ThemeKey?,
        catalogDefaultThemeId: ThemeKey?,
        templateModel: TemplateDocument,
        templateCatalogKey: CatalogKey? = null,
        catalogDefaultThemeCatalogKey: CatalogKey? = null,
        dependencies: DraftDependencyView? = null,
    ): ResolvedStyles {
        val theme = resolveTheme(
            tenantId = tenantId,
            templateDefaultThemeId = templateDefaultThemeId,
            catalogDefaultThemeId = catalogDefaultThemeId,
            templateModel = templateModel,
            templateCatalogKey = templateCatalogKey,
            catalogDefaultThemeCatalogKey = catalogDefaultThemeCatalogKey,
            dependencies = dependencies,
        )
        val templateDocumentStyles = templateModel.documentStylesOverride ?: emptyMap()

        return if (theme != null) {
            ResolvedStyles(
                documentStyles = mergeDocumentStyles(theme.documentStyles, templateDocumentStyles),
                pageSettings = theme.pageSettings, // Theme page settings as fallback
                blockStylePresets = theme.blockStylePresets ?: BlockStylePresets.EMPTY,
                spacingUnit = theme.spacingUnit ?: SpacingScale.DEFAULT_BASE_UNIT,
            )
        } else {
            ResolvedStyles(
                documentStyles = templateDocumentStyles,
                pageSettings = null,
                blockStylePresets = BlockStylePresets.EMPTY,
            )
        }
    }

    /**
     * Returns the unmerged effective theme selected by the same cascade used
     * for generation. Callers that apply template overrides themselves (such
     * as the editor) must use this rather than [resolveStyles], otherwise an
     * override would be baked into the theme fallback and could not be cleared.
     *
     * With [dependencies], a theme of another catalog comes from where the draft's declarations say:
     * the release it pins, or the draft it names.
     */
    fun resolveTheme(
        tenantId: TenantKey,
        templateDefaultThemeId: ThemeKey?,
        catalogDefaultThemeId: ThemeKey?,
        templateModel: TemplateDocument,
        templateCatalogKey: CatalogKey? = null,
        catalogDefaultThemeCatalogKey: CatalogKey? = null,
        dependencies: DraftDependencyView? = null,
    ): Theme? {
        // Theme cascade: variant-level > template-level > tenant-level
        // The catalog key must follow the theme key through the cascade
        val (effectiveThemeId, effectiveCatalogKey) = when (val ref = templateModel.themeRef) {
            is ThemeRefOverride -> ThemeKey.of(ref.themeKey) to (ref.catalogKey?.let { CatalogKey.of(it) } ?: templateCatalogKey)
            else -> null to null
        }.let { (themeId, catalogKey) ->
            if (themeId != null) {
                themeId to catalogKey
            } else if (templateDefaultThemeId != null) {
                templateDefaultThemeId to templateCatalogKey
            } else if (catalogDefaultThemeId != null) {
                catalogDefaultThemeId to catalogDefaultThemeCatalogKey
            } else {
                null to null
            }
        }

        return effectiveThemeId?.let { getTheme(tenantId, effectiveCatalogKey, it, dependencies) }
    }

    /**
     * Gets a theme by ID for a tenant, optionally scoped to a catalog.
     */
    private fun getTheme(tenantId: TenantKey, catalogKey: CatalogKey?, themeId: ThemeKey, dependencies: DraftDependencyView?): Theme? {
        if (catalogKey != null) dependencies?.releaseOf(catalogKey)?.let { release -> return releasedTheme(tenantId, release, themeId, dependencies) }
        val draft = catalogKey?.let { dependencies?.draftOf(it) }
        return jdbi.withHandle<Theme?, Exception> { handle -> getTheme(handle, tenantId, catalogKey, themeId, draft?.versionId) }
    }

    /** [themeId] as the release [release] holds it, in the shape of a stored theme. */
    private fun releasedTheme(tenantId: TenantKey, release: DraftDependencyView.Source.Release, themeId: ThemeKey, dependencies: DraftDependencyView): Theme? {
        val theme = dependencies.released(release.catalogKey, "theme", themeId.value) as? ThemeResource ?: return null
        val at = EpistolaClock.offsetDateTime()
        return Theme(
            id = themeId,
            tenantKey = tenantId,
            catalogKey = release.catalogKey,
            name = theme.name,
            description = theme.description,
            documentStyles = protocolMapper.mapToDocumentStyles(theme.documentStyles),
            pageSettings = theme.pageSettings,
            blockStylePresets = protocolMapper.mapToBlockStylePresets(theme.blockStylePresets),
            spacingUnit = theme.spacingUnit,
            createdAt = at,
            updatedAt = at,
        )
    }

    private fun getTheme(handle: Handle, tenantId: TenantKey, catalogKey: CatalogKey?, themeId: ThemeKey, versionId: UUID?): Theme? {
        val query = when {
            versionId != null -> handle.createQuery(
                """
                SELECT t.*, c.catalog_key AS catalog_key, c.type AS catalog_type FROM themes t JOIN catalogs c ON c.tenant_key = t.tenant_key AND c.catalog_id = t.catalog_id
                WHERE t.id = :id AND t.tenant_key = :tenantId AND t.version_id = :versionId
                """,
            ).bind("versionId", versionId)

            catalogKey != null -> handle.createQuery(
                """
                SELECT t.*, c.catalog_key AS catalog_key, c.type AS catalog_type FROM themes t JOIN catalogs c ON c.tenant_key = t.tenant_key AND c.catalog_id = t.catalog_id
                WHERE t.id = :id AND t.tenant_key = :tenantId AND ${inCurrentVersion("t")} AND t.version_id = ${versionAt("tenantId", "catalogKey")}
                """,
            ).bind("catalogKey", catalogKey)

            else -> handle.createQuery(
                """
                SELECT t.*, c.catalog_key AS catalog_key, c.type AS catalog_type FROM themes t JOIN catalogs c ON c.tenant_key = t.tenant_key AND c.catalog_id = t.catalog_id
                WHERE t.id = :id AND t.tenant_key = :tenantId AND ${inCurrentVersion("t")}
                """,
            )
        }
            .bind("id", themeId)
            .bind("tenantId", tenantId)
        return query.mapTo<Theme>()
            .findOne()
            .orElse(null)
    }

    /**
     * Merges theme and template document styles.
     * Template styles override theme styles where both are defined.
     */
    private fun mergeDocumentStyles(themeStyles: DocumentStyles, templateStyles: DocumentStyles): DocumentStyles = themeStyles + templateStyles

    companion object {
        /**
         * Resolves block styles by merging preset styles with inline styles.
         * Inline styles override preset styles.
         *
         * This is a static utility that can be used by the generation module without
         * requiring database access.
         *
         * @param blockStylePresets The presets from the theme
         * @param presetName The name of the preset referenced by the block (may be null)
         * @param inlineStyles The block's inline styles (may be null)
         * @return Merged styles map with inline styles taking precedence
         */
        fun resolveBlockStyles(
            blockStylePresets: BlockStylePresets,
            presetName: String?,
            inlineStyles: Map<String, Any>?,
        ): Map<String, Any>? {
            val presetStyles = presetName?.let { blockStylePresets[it]?.styles }

            return when {
                presetStyles == null && inlineStyles == null -> null
                presetStyles == null -> inlineStyles
                inlineStyles == null -> presetStyles
                else -> presetStyles + inlineStyles // inline styles override preset
            }
        }
    }
}
