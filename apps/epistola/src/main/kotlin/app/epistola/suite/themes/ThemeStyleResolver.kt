package app.epistola.suite.themes

import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.templates.model.DocumentStyles
import app.epistola.suite.templates.model.PageSettings
import app.epistola.template.model.TemplateModel
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
    val blockStylePresets: Map<String, Map<String, Any>>,
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
 * 1. Variant-level theme (TemplateModel.themeId) - highest priority
 * 2. Template-level default theme (DocumentTemplate.themeId) - fallback
 * 3. No theme - if neither is set
 */
@Service
class ThemeStyleResolver(
    private val jdbi: Jdbi,
) {
    /**
     * Resolves document-level styles by merging theme styles with template styles.
     * Uses the variant-level theme from TemplateModel if set, otherwise no theme.
     *
     * @param tenantId The tenant ID for theme lookup
     * @param templateModel The template model containing themeId and template-level styles
     * @return Resolved styles combining theme and template settings
     */
    fun resolveStyles(tenantId: TenantId, templateModel: TemplateModel): ResolvedStyles = resolveStyles(tenantId, templateDefaultThemeId = null, templateModel = templateModel)

    /**
     * Resolves document-level styles with support for both template-level and variant-level themes.
     * Variant-level theme (in TemplateModel) overrides template-level default theme.
     *
     * @param tenantId The tenant ID for theme lookup
     * @param templateDefaultThemeId The default theme from DocumentTemplate (may be null)
     * @param templateModel The template model containing optional themeId override and styles
     * @return Resolved styles combining theme and template settings
     */
    fun resolveStyles(
        tenantId: TenantId,
        templateDefaultThemeId: ThemeId?,
        templateModel: TemplateModel,
    ): ResolvedStyles {
        // Variant-level theme overrides template-level default
        val effectiveThemeId = templateModel.themeId?.let { ThemeId.of(UUID.fromString(it)) }
            ?: templateDefaultThemeId

        val theme = effectiveThemeId?.let { getTheme(tenantId, it) }

        return if (theme != null) {
            ResolvedStyles(
                documentStyles = mergeDocumentStyles(theme.documentStyles, templateModel.documentStyles),
                pageSettings = theme.pageSettings, // Theme page settings as fallback
                blockStylePresets = theme.blockStylePresets ?: emptyMap(),
            )
        } else {
            ResolvedStyles(
                documentStyles = templateModel.documentStyles,
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
    private fun mergeDocumentStyles(themeStyles: DocumentStyles, templateStyles: DocumentStyles): DocumentStyles = DocumentStyles(
        fontFamily = templateStyles.fontFamily ?: themeStyles.fontFamily,
        fontSize = templateStyles.fontSize ?: themeStyles.fontSize,
        fontWeight = templateStyles.fontWeight ?: themeStyles.fontWeight,
        color = templateStyles.color ?: themeStyles.color,
        lineHeight = templateStyles.lineHeight ?: themeStyles.lineHeight,
        letterSpacing = templateStyles.letterSpacing ?: themeStyles.letterSpacing,
        textAlign = templateStyles.textAlign ?: themeStyles.textAlign,
        backgroundColor = templateStyles.backgroundColor ?: themeStyles.backgroundColor,
    )

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
            blockStylePresets: Map<String, Map<String, Any>>,
            presetName: String?,
            inlineStyles: Map<String, Any>?,
        ): Map<String, Any>? {
            val presetStyles = presetName?.let { blockStylePresets[it] }

            return when {
                presetStyles == null && inlineStyles == null -> null
                presetStyles == null -> inlineStyles
                inlineStyles == null -> presetStyles
                else -> presetStyles + inlineStyles // inline styles override preset
            }
        }
    }
}
