// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.catalog.queries.ListCatalogDependencies
import app.epistola.suite.catalog.versions.CatalogDefaultTheme
import app.epistola.suite.catalog.versions.CatalogPreset
import app.epistola.suite.catalog.versions.GetCatalogThemeSettings
import app.epistola.suite.catalog.versions.SetCatalogDefaultTheme
import app.epistola.suite.catalog.versions.SetCatalogPresets
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.htmx.CatalogSegment
import app.epistola.suite.htmx.catalogSegment
import app.epistola.suite.htmx.htmx
import app.epistola.suite.htmx.redirect
import app.epistola.suite.htmx.tenantId
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.requirePermission
import app.epistola.suite.themes.queries.ListThemes
import app.epistola.suite.validation.ValidationException
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse

/**
 * A catalog version's theme settings on its Details page (contract ADR 0007): the default theme its
 * templates that name none render with, and the block style presets it declares. Loaded into the page
 * after it, and saved in place.
 */
@Component
class CatalogThemeSettingsHandler {

    /** A theme the default can be: one of the catalog's own, or of a catalog it depends on. */
    data class ThemeOption(val value: String, val label: String)

    fun show(request: ServerRequest): ServerResponse = section(request, saved = false, error = null)

    fun save(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        requirePermission(tenantId.key, Permission.TEMPLATE_EDIT)
        val segment = request.catalogSegment()
        val form = request.params()
        val error = try {
            val theme = form.getFirst("defaultTheme")?.takeIf { it.contains('/') }?.let {
                CatalogDefaultTheme(ThemeKey.of(it.substringAfter('/')), CatalogKey.of(it.substringBefore('/')))
            }
            val keys = form["presetKey"].orEmpty()
            val names = form["presetName"].orEmpty()
            val descriptions = form["presetDescription"].orEmpty()
            val removed = form["presetRemove"].orEmpty().toSet()
            val presets = keys.indices
                .filter { keys[it].isNotBlank() && keys[it] !in removed }
                .map { CatalogPreset(keys[it].trim(), names.getOrNull(it)?.trim().orEmpty().ifBlank { keys[it].trim() }, descriptions.getOrNull(it)?.trim()?.ifBlank { null }) }
            SetCatalogDefaultTheme(tenantId.key, segment.key, theme, catalogVersion = segment.ref(tenantId.key)).execute()
            SetCatalogPresets(tenantId.key, segment.key, presets, catalogVersion = segment.ref(tenantId.key)).execute()
            null
        } catch (e: ValidationException) {
            e.message
        }
        return section(request, saved = error == null, error = error)
    }

    private fun section(request: ServerRequest, saved: Boolean, error: String?): ServerResponse {
        val tenantId = request.tenantId()
        val segment = request.catalogSegment()
        val catalog = GetCatalog(tenantId.key, segment.key).query() ?: return ServerResponse.notFound().build()
        val settings = GetCatalogThemeSettings(tenantId.key, segment.key, catalogVersion = segment.ref(tenantId.key)).query()
        return request.htmx {
            fragment("catalogs/theme-settings", "section") {
                "tenantId" to tenantId.key
                "segmentValue" to segment.value
                "editable" to (catalog.type.name == "AUTHORED")
                "settings" to settings
                "defaultThemeValue" to settings.defaultTheme?.let { "${it.catalogKey.value}/${it.themeKey.value}" }
                "themeOptions" to themeOptions(tenantId.key, segment)
                "saved" to saved
                "error" to error
            }
            onFullPage { redirect("/tenants/${tenantId.key}/catalogs/${segment.value}/settings") }
        }
    }

    private fun themeOptions(tenantKey: app.epistola.suite.common.ids.TenantKey, segment: CatalogSegment): List<ThemeOption> {
        val own = ListThemes(TenantId(tenantKey), catalogKey = segment.key, catalogVersion = segment.ref(tenantKey)).query()
        val others = ListCatalogDependencies(tenantKey, segment.key, segment.ref(tenantKey)).query()
            .map { CatalogKey.of(it.catalogKey) }
            .distinct()
            .flatMap { ListThemes(TenantId(tenantKey), catalogKey = it).query() }
        return (own + others).map { ThemeOption("${it.catalogKey.value}/${it.id.value}", "${it.name} (${it.catalogKey.value})") }
    }
}
