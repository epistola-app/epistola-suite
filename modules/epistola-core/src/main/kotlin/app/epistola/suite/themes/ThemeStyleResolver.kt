package app.epistola.suite.themes

import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.ThemeId
import app.epistola.template.model.DocumentStyles
import app.epistola.template.model.PageSettings
import app.epistola.template.model.TemplateDocument
import app.epistola.template.model.ThemeRefOverride
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Service

/**
 * Resolved styling information combining theme and template settings.
 */
data class ResolvedStyles(
    val documentStyles: DocumentStyles,
    val pageSettings: PageSettings?,
    val blockStylePresets: Map<String, BlockStylePreset>,
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
) {
    /**
     * Resolves document-level styles by merging theme styles with template styles.
     * Uses the variant-level theme from TemplateDocument's themeRef if set, otherwise no theme.
     *
     * @param tenantId The tenant ID for theme lookup
     * @param templateModel The template document containing themeRef and template-level styles
     * @return Resolved styles combining theme and template settings
     */
    fun resolveStyles(tenantId: TenantId, templateModel: TemplateDocument): ResolvedStyles = resolveStyles(tenantId, templateDefaultThemeId = null, tenantDefaultThemeId = null, templateModel = templateModel)

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
        tenantId: TenantId,
        templateDefaultThemeId: ThemeId?,
        templateModel: TemplateDocument,
    ): ResolvedStyles = resolveStyles(tenantId, templateDefaultThemeId, tenantDefaultThemeId = null, templateModel)

    /**
     * Resolves document-level styles with full theme cascade support.
     *
     * Theme cascade order:
     * 1. Variant-level theme (TemplateDocument.themeRef override) - highest priority
     * 2. Template-level default theme (templateDefaultThemeId) - fallback
     * 3. Tenant default theme (tenantDefaultThemeId) - ultimate fallback
     *
     * @param tenantId The tenant ID for theme lookup
     * @param templateDefaultThemeId The default theme from DocumentTemplate (may be null)
     * @param tenantDefaultThemeId The default theme from Tenant (may be null)
     * @param templateModel The template document containing optional themeRef override and styles
     * @return Resolved styles combining theme and template settings
     */
    fun resolveStyles(
        tenantId: TenantId,
        templateDefaultThemeId: ThemeId?,
        tenantDefaultThemeId: ThemeId?,
        templateModel: TemplateDocument,
    ): ResolvedStyles {
        // Theme cascade: variant-level > template-level > tenant-level
        val effectiveThemeId = when (val ref = templateModel.themeRef) {
            is ThemeRefOverride -> ThemeId.of(ref.themeId)
            else -> null
        } ?: templateDefaultThemeId ?: tenantDefaultThemeId

        val theme = effectiveThemeId?.let { getTheme(tenantId, it) }
        val templateDocumentStyles = templateModel.documentStylesOverride ?: emptyMap()

        return if (theme != null) {
            ResolvedStyles(
                documentStyles = mergeDocumentStyles(theme.documentStyles, templateDocumentStyles),
                pageSettings = theme.pageSettings, // Theme page settings as fallback
                blockStylePresets = theme.blockStylePresets ?: emptyMap(),
            )
        } else {
            ResolvedStyles(
                documentStyles = templateDocumentStyles,
                pageSettings = null,
                blockStylePresets = emptyMap(),
            )
        }
    }

    /**
     * Gets a theme by ID for a tenant.
     */
    private fun getTheme(tenantId: TenantId, themeId: ThemeId): Theme? = jdbi.withHandle<Theme?, Exception> { handle ->
        handle.createQuery(
            """
            SELECT * FROM themes WHERE id = :id AND tenant_id = :tenantId
            """,
        )
            .bind("id", themeId)
            .bind("tenantId", tenantId)
            .mapTo<Theme>()
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
            blockStylePresets: Map<String, BlockStylePreset>,
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
