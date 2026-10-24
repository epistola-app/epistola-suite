// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.themes

import app.epistola.catalog.protocol.ThemeResource
import app.epistola.generation.pdf.SpacingScale
import app.epistola.suite.catalog.ProtocolMapper
import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.dependencies.DraftDependencyView
import app.epistola.suite.catalog.inCurrentVersion
import app.epistola.suite.catalog.versionAt
import app.epistola.suite.common.ids.CatalogId
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
    private val references: app.epistola.suite.catalog.CatalogReferences,
) {
    /**
     * Resolves document-level styles with the theme cascade (contract ADR 0007): the variant's own
     * theme (the template model's `themeRef`), then the template's default theme, then the catalog's.
     * Each theme is of a catalog: the template's default theme of [templateThemeCatalog], the
     * catalog's of [catalogDefaultThemeCatalog], both [templateCatalog] when null; the variant's names
     * its catalog by alias, resolved from [templateCatalog] (ADR 0034, section 9). Template styles
     * override the theme's.
     */
    fun resolveStyles(
        templateCatalog: CatalogId,
        templateDefaultThemeId: ThemeKey?,
        catalogDefaultThemeId: ThemeKey?,
        templateModel: TemplateDocument,
        templateThemeCatalog: CatalogId? = null,
        catalogDefaultThemeCatalog: CatalogId? = null,
        dependencies: DraftDependencyView? = null,
    ): ResolvedStyles {
        val theme = resolveTheme(templateCatalog, templateDefaultThemeId, catalogDefaultThemeId, templateModel, templateThemeCatalog, catalogDefaultThemeCatalog, dependencies)
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
        templateCatalog: CatalogId,
        templateDefaultThemeId: ThemeKey?,
        catalogDefaultThemeId: ThemeKey?,
        templateModel: TemplateDocument,
        templateThemeCatalog: CatalogId? = null,
        catalogDefaultThemeCatalog: CatalogId? = null,
        dependencies: DraftDependencyView? = null,
    ): Theme? {
        val ref = templateModel.themeRef
        val (themeId, catalog) = when {
            ref is ThemeRefOverride -> {
                // The variant's theme, by alias from the template's catalog.
                val catalog = dependencies?.resolve(templateCatalog, ref.catalogKey) ?: references.resolve(templateCatalog, ref.catalogKey) ?: return null
                ThemeKey.of(ref.themeKey) to catalog
            }

            templateDefaultThemeId != null -> templateDefaultThemeId to (templateThemeCatalog ?: templateCatalog)

            catalogDefaultThemeId != null -> catalogDefaultThemeId to (catalogDefaultThemeCatalog ?: templateCatalog)

            else -> return null
        }
        return getTheme(catalog, themeId, dependencies)
    }

    /**
     * Gets a theme by ID for a tenant, optionally scoped to a catalog.
     */
    private fun getTheme(catalog: CatalogId, themeId: ThemeKey, dependencies: DraftDependencyView?): Theme? {
        dependencies?.releaseOf(catalog)?.let { release -> return releasedTheme(release, themeId, dependencies) }
        val draft = dependencies?.draftOf(catalog)
        return jdbi.withHandle<Theme?, Exception> { handle -> getTheme(handle, catalog, themeId, draft?.versionId) }
    }

    /** [themeId] as the release [release] holds it, in the shape of a stored theme. */
    private fun releasedTheme(release: DraftDependencyView.Source.Release, themeId: ThemeKey, dependencies: DraftDependencyView): Theme? {
        val theme = dependencies.released(release.catalog, "theme", themeId.value) as? ThemeResource ?: return null
        val at = EpistolaClock.offsetDateTime()
        return Theme(
            id = themeId,
            tenantKey = release.catalog.tenantKey,
            catalogKey = release.catalog.key,
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

    private fun getTheme(handle: Handle, catalog: CatalogId, themeId: ThemeKey, versionId: UUID?): Theme? {
        val query = when {
            versionId != null -> handle.createQuery(
                """
                SELECT t.*, c.catalog_key AS catalog_key FROM themes t JOIN catalogs c ON c.tenant_key = t.tenant_key AND c.catalog_id = t.catalog_id
                WHERE t.id = :id AND t.tenant_key = :tenantKey AND t.version_id = :versionId
                """,
            ).bind("versionId", versionId)

            else -> handle.createQuery(
                """
                SELECT t.*, c.catalog_key AS catalog_key FROM themes t JOIN catalogs c ON c.tenant_key = t.tenant_key AND c.catalog_id = t.catalog_id
                WHERE t.id = :id AND t.tenant_key = :tenantKey AND t.version_id = ${versionAt("tenantKey", "catalogKey")}
                """,
            ).bindCatalog("catalogKey", catalog)
        }
            .bind("id", themeId)
            .bind("tenantKey", catalog.tenantKey)
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
