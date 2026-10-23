// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.themes

import app.epistola.suite.catalog.Catalog
import app.epistola.suite.catalog.queries.ListCatalogs
import app.epistola.suite.catalog.releases.ApplyHouseTheme
import app.epistola.suite.catalog.releases.CatalogDefaultTheme
import app.epistola.suite.catalog.releases.GetCatalogThemeSettings
import app.epistola.suite.catalog.releases.SetCatalogDefaultTheme
import app.epistola.suite.common.ResourceModifiedException
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.handlers.CatalogDraftBar
import app.epistola.suite.handlers.CatalogPages
import app.epistola.suite.handlers.FromOtherCatalog
import app.epistola.suite.handlers.FromOtherCatalogs
import app.epistola.suite.htmx.CatalogSegment
import app.epistola.suite.htmx.ModelBuilder
import app.epistola.suite.htmx.catalogId
import app.epistola.suite.htmx.catalogParam
import app.epistola.suite.htmx.catalogSegment
import app.epistola.suite.htmx.catalogVersion
import app.epistola.suite.htmx.executeOrFormError
import app.epistola.suite.htmx.form
import app.epistola.suite.htmx.htmx
import app.epistola.suite.htmx.page
import app.epistola.suite.htmx.queryParam
import app.epistola.suite.htmx.redirect
import app.epistola.suite.htmx.tenantId
import app.epistola.suite.htmx.themeId
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.requirePermission
import app.epistola.suite.templates.model.DocumentStyles
import app.epistola.suite.templates.model.PageSettings
import app.epistola.suite.tenants.commands.SetTenantDefaultTheme
import app.epistola.suite.tenants.queries.GetTenant
import app.epistola.suite.themes.commands.CreateTheme
import app.epistola.suite.themes.commands.DeleteTheme
import app.epistola.suite.themes.commands.UpdateTheme
import app.epistola.suite.themes.queries.GetTheme
import app.epistola.suite.themes.queries.GetThemeUsagePage
import app.epistola.suite.themes.queries.ListThemes
import app.epistola.suite.themes.queries.ThemeUsagePage
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse
import tools.jackson.databind.ObjectMapper

private const val THEME_USAGE_PAGE_SIZE = 50

/**
 * Request body for updating a theme via PATCH.
 */
data class UpdateThemeRequest(
    val name: String? = null,
    val description: String? = null,
    val clearDescription: Boolean = false,
    val documentStyles: DocumentStyles? = null,
    val pageSettings: PageSettings? = null,
    val clearPageSettings: Boolean = false,
    val blockStylePresets: BlockStylePresets? = null,
    val clearBlockStylePresets: Boolean = false,
    val spacingUnit: Float? = null,
    val clearSpacingUnit: Boolean = false,
    /** The theme's revision as the editor last read or saved it; a save over someone else's is refused. */
    val expectedRevision: Long? = null,
)

@Component
class ThemeHandler(
    private val objectMapper: ObjectMapper,
) {
    fun list(request: ServerRequest): ServerResponse = ServerResponse.ok().page("themes/list") { themePageModel(request) }

    fun search(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val searchTerm = request.queryParam("q")
        val segment = request.catalogParam()
        val catalogFilter = segment?.key
        val tenant = GetTenant(id = tenantId.key).query()
        val themes = ListThemes(tenantId = tenantId, searchTerm = searchTerm, catalogKey = catalogFilter, catalogVersion = segment?.ref(tenantId.key)).query()
        return request.htmx {
            fragment("themes/list", "rows") {
                "tenantId" to tenantId.key
                "tenant" to tenant
                "themes" to themes
                "draftSegment" to segment
                "catalogDefault" to catalogDefaultOf(tenantId.key, segment)
                // Lets the fragment tell "no matches" apart from "none yet". A search swaps only
                // this fragment, so the distinction has to reach it.
                "searchTerm" to searchTerm.orEmpty()
            }
            onFullPage { redirect(CatalogPages.list(request, tenantId.key, "themes")) }
        }
    }

    /**
     * The catalog's themes as the list page shows them: the page itself, and behind the create dialog
     * when `/new` is opened directly or a create is refused without HTMX. The segment may name a draft
     * (`letters@<draft>`); its rows are then the draft's.
     */
    private fun ModelBuilder.themePageModel(request: ServerRequest) {
        val tenantId = request.tenantId()
        val segment = request.catalogSegment()
        val bar = CatalogDraftBar.of(tenantId.key, segment) { "/tenants/${tenantId.key}/catalogs/${it.value}/themes" }
        "pageTitle" to "Themes - Epistola"
        "tenantId" to tenantId.key
        "tenant" to GetTenant(id = tenantId.key).query()
        "catalogs" to ListCatalogs(tenantId.key).query()
        "selectedCatalog" to segment.value
        "draftSegment" to segment
        "draftBar" to bar
        "catalogDefault" to catalogDefaultOf(tenantId.key, segment)
        "houseThemeApplied" to request.param("houseThemeApplied").orElse(null)
        // What the catalogs this one depends on hold of this kind, listed read-only below its own.
        "fromOtherCatalogs" to FromOtherCatalogs.of(bar, { ListThemes(tenantId = tenantId, catalogKey = it).query() }) { t, c, n -> FromOtherCatalog(t.name, "/tenants/${tenantId.key}/catalogs/${c.value}/themes/${t.id.value}", n) }
        "themes" to ListThemes(tenantId = tenantId, catalogKey = segment.key, catalogVersion = segment.ref(tenantId.key)).query()
    }

    fun newForm(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        requirePermission(tenantId.key, Permission.THEME_EDIT)
        return request.htmx {
            // In-app trigger (hx-get → #dialog-mount): just the dialog fragment.
            fragment("themes/new", "dialog") {
                "tenantId" to tenantId.key
                // Opened from a list filtered to a draft, the dialog creates in that draft.
                // The catalog it was opened on, a draft or plain: a dialog opened from a catalog's page
                // creates there, rather than in whichever catalog sorts first.
                "draftSegment" to request.catalogParam()
            }
            // Direct navigation / boost: the host list page with the dialog
            // embedded in its mount (openDialog=true), opened on load by the JS.
            onFullPage {
                page("themes/list") {
                    themePageModel(request)
                    "openDialog" to true
                }
            }
        }
    }

    fun create(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        requirePermission(tenantId.key, Permission.THEME_EDIT)

        val form = request.form {
            field("slug") {
                required()
                pattern("^[a-z][a-z0-9]*(-[a-z0-9]+)*$")
                minLength(3)
                maxLength(20)
                // Folds the old "invalid ThemeKey" branch into field validation
                // (same "Invalid theme ID format" error) so all three failure
                // modes share one error path. Reported only when no rule above
                // failed first, so the specific message wins.
                asThemeId()
            }
            field("name") {
                required()
                maxLength(100)
            }
            field("description") {}
        }

        val description = form["description"].trim().takeIf { it.isNotEmpty() }

        // Field validation (incl. catalog/CatalogKey and slug/ThemeKey) and the
        // command-level failure (duplicate slug, name length) both land as `errors`
        // on the FormData, so they share one error path — mirroring
        // DocumentTemplateHandler.create.
        val result = if (form.hasErrors()) {
            form
        } else {
            form.executeOrFormError {
                val segment = request.catalogSegment()
                CreateTheme(
                    id = ThemeId(
                        ThemeKey.validateOrNull(form["slug"])!!,
                        CatalogId(segment.key, tenantId),
                    ),
                    name = form["name"],
                    description = description,
                    catalogVersion = segment.ref(tenantId.key),
                ).execute()
            }
        }

        if (result.hasErrors()) {
            return request.htmx {
                // Re-render the form inside the dialog (retargeted to the form, not
                // the list) with inline errors + preserved values. `tenantId` is the
                // prefill the form fragment needs to rebuild its action URL.
                dialogFieldErrors(
                    template = "themes/new",
                    fragmentName = "theme-form",
                    formTarget = "#create-theme-form",
                    formData = result,
                ) {
                    "tenantId" to tenantId.key
                    "draftSegment" to request.catalogParam()
                }
                onFullPage {
                    page(422, "themes/list") {
                        themePageModel(request)
                        "openDialog" to true
                        "formData" to result.formData
                        "errors" to result.errors
                    }
                }
            }
        }

        // Success: navigate to the newly created theme's page. Soft boosted
        // navigation (HX-Location) — the same body-swap a theme click from the list
        // already performs, so the theme editor's body-hosted boot script re-runs
        // as usual; the dialog goes with the swapped-out body. (Experiment: was
        // HX-Redirect; trying the soft path to drop the full-page reload.)
        // Safe !!/of(): both fields were validated above and the create succeeded.
        val themeKey = ThemeKey.validateOrNull(form["slug"])!!
        val catalogKey = request.catalogSegment().value
        val destination = "/tenants/${tenantId.key}/catalogs/$catalogKey/themes/$themeKey"
        return request.htmx {
            dialogLocation(destination)
            onFullPage { redirect(destination) }
        }
    }

    fun detail(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val catalogId = request.catalogId()
        val segment = request.catalogSegment()
        val themeId = request.themeId(tenantId)
            ?: return ServerResponse.badRequest().build()
        val draftBar = CatalogDraftBar.of(tenantId.key, segment, CatalogDraftBar.inPath(request.path(), segment))

        val theme = GetTheme(id = themeId, catalogVersion = draftBar.ref).query()
            ?: return ServerResponse.notFound().build()

        // Serialize theme data as JSON for the Lit component
        val themeJson = mapOf(
            "id" to theme.id.value,
            "name" to theme.name,
            "description" to theme.description,
            "documentStyles" to theme.documentStyles,
            "pageSettings" to theme.pageSettings,
            "blockStylePresets" to (theme.blockStylePresets ?: emptyMap()),
            "spacingUnit" to theme.spacingUnit,
            "revision" to theme.revision,
        )

        val editable = draftBar.editable

        return ServerResponse.ok().page("themes/detail") {
            "pageTitle" to "${theme.name} - Epistola"
            "tenantId" to tenantId.key
            "catalogId" to catalogId.value
            "catalogSegment" to segment.value
            "draftBar" to draftBar
            "theme" to theme
            "themeJson" to themeJson
            "editable" to editable
        }
    }

    fun usage(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val themeId = request.themeId(tenantId)
            ?: return ServerResponse.badRequest().build()
        val version = request.catalogVersion()
        val theme = GetTheme(id = themeId, catalogVersion = version).query()
            ?: return ServerResponse.notFound().build()

        val pageParam = request.param("page").orElse(null)
        val page = pageParam?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val usagePage = GetThemeUsagePage(
            themeId = themeId,
            limit = THEME_USAGE_PAGE_SIZE,
            offset = (page - 1) * THEME_USAGE_PAGE_SIZE,
            catalogVersion = version,
        ).query()

        return request.htmx {
            fragment("themes/usage", if (pageParam == null) "dialog" else "usage") {
                "tenantId" to tenantId.key
                "catalogId" to themeId.catalogKey.value
                "catalogSegment" to request.catalogSegment().value
                "draftSegment" to request.catalogSegment().takeIf { it.draft != null }
                "theme" to theme
                themeUsageModel(usagePage, page)
            }
            onFullPage {
                redirect(
                    "/tenants/${tenantId.key}/catalogs/${themeId.catalogKey}/themes/${themeId.key}",
                )
            }
        }
    }

    private fun ModelBuilder.themeUsageModel(usagePage: ThemeUsagePage, page: Int) {
        "usage" to usagePage.items
        "usageTotal" to usagePage.total
        "usagePage" to page
        "usageTotalPages" to maxOf(1, (usagePage.total + THEME_USAGE_PAGE_SIZE - 1) / THEME_USAGE_PAGE_SIZE)
    }

    fun update(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val themeId = request.themeId(tenantId)
            ?: return ServerResponse.badRequest().build()

        val body = request.body(String::class.java)
        val updateRequest = objectMapper.readValue(body, UpdateThemeRequest::class.java)

        val theme = try {
            UpdateTheme(
                id = themeId,
                name = updateRequest.name,
                description = updateRequest.description,
                clearDescription = updateRequest.clearDescription,
                documentStyles = updateRequest.documentStyles,
                pageSettings = updateRequest.pageSettings,
                clearPageSettings = updateRequest.clearPageSettings,
                blockStylePresets = updateRequest.blockStylePresets,
                clearBlockStylePresets = updateRequest.clearBlockStylePresets,
                spacingUnit = updateRequest.spacingUnit,
                clearSpacingUnit = updateRequest.clearSpacingUnit,
                catalogVersion = request.catalogVersion(),
                expectedRevision = updateRequest.expectedRevision,
            ).execute()
        } catch (e: ResourceModifiedException) {
            return ServerResponse.status(HttpStatus.PRECONDITION_FAILED)
                .contentType(MediaType.APPLICATION_JSON)
                .body(mapOf("error" to "Someone else saved this theme after you opened it."))
        } ?: return ServerResponse.notFound().build()

        return ServerResponse.ok()
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                mapOf(
                    "id" to theme.id.value,
                    "name" to theme.name,
                    "description" to theme.description,
                    "documentStyles" to theme.documentStyles,
                    "pageSettings" to theme.pageSettings,
                    "blockStylePresets" to theme.blockStylePresets,
                    "spacingUnit" to theme.spacingUnit,
                    "updatedAt" to theme.updatedAt,
                    "revision" to theme.revision,
                ),
            )
    }

    fun delete(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val themeId = request.themeId(tenantId)
            ?: return ServerResponse.badRequest().build()

        // The detail page's Delete: a refusal shows in its form, and done, the list it came from.
        val fromDetail = request.param("from").orElse("") == "detail"
        try {
            DeleteTheme(id = themeId, catalogVersion = request.catalogVersion()).execute()
        } catch (e: ThemeInUseException) {
            if (fromDetail) {
                return request.htmx {
                    globalFormError("delete-theme-error", e.message ?: "The theme is in use.")
                    onFullPage { redirect(request.path().removeSuffix("/delete")) }
                }
            }
            return ServerResponse.badRequest()
                .contentType(MediaType.APPLICATION_JSON)
                .body(mapOf("error" to e.message))
        }
        if (fromDetail) {
            val list = CatalogPages.list(request, tenantId.key, "themes")
            return request.htmx {
                dialogLocation(list)
                onFullPage { redirect(list) }
            }
        }

        // The catalog's rows, refreshed in place.
        val segment = request.catalogSegment()
        return request.htmx {
            fragment("themes/list", "rows") {
                "tenantId" to tenantId.key
                "tenant" to GetTenant(id = tenantId.key).query()
                "draftSegment" to segment
                "themes" to ListThemes(tenantId = tenantId, catalogKey = segment.key, catalogVersion = segment.ref(tenantId.key)).query()
            }
            onFullPage { redirect(CatalogPages.list(request, tenantId.key, "themes")) }
        }
    }

    /**
     * Sets (or clears) the default theme for a tenant. An empty/missing
     * `themeId` form param clears the default; templates without their own
     * theme then render with engine defaults.
     */
    fun setDefault(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val themeIdStr = request.params().getFirst("themeId")?.takeIf { it.isNotBlank() }
        val catalogKeyStr = request.params().getFirst("catalogKey")?.takeIf { it.isNotBlank() }

        val themeKey = themeIdStr?.let { ThemeKey.validateOrNull(it) ?: return ServerResponse.badRequest().build() }
        val catalogKey = catalogKeyStr?.let { CatalogKey.of(it) }

        try {
            SetTenantDefaultTheme(
                tenantId = tenantId.key,
                themeId = themeKey,
                catalogKey = catalogKey,
            ).execute()
        } catch (e: ThemeNotFoundException) {
            return ServerResponse.notFound().build()
        }

        // The rows of the list it was set from: the chosen theme's catalog (`catalog`, the list's segment).
        val segment = request.catalogParam() ?: catalogKey?.let { CatalogSegment(it) }
        return request.htmx {
            fragment("themes/list", "rows") {
                "tenantId" to tenantId.key
                "tenant" to GetTenant(id = tenantId.key).query()
                "draftSegment" to segment
                "catalogDefault" to catalogDefaultOf(tenantId.key, segment)
                "themes" to ListThemes(tenantId = tenantId, catalogKey = segment?.key, catalogVersion = segment?.ref(tenantId.key)).query()
            }
            onFullPage { redirect(CatalogPages.list(request, tenantId.key, "themes")) }
        }
    }

    /** The default theme of the catalog version a themes list shows (contract ADR 0007); null for none. */
    private fun catalogDefaultOf(tenantKey: TenantKey, segment: CatalogSegment?): CatalogDefaultTheme? = segment?.let { GetCatalogThemeSettings(tenantKey, it.key, catalogVersion = it.ref(tenantKey)).query().defaultTheme }

    /**
     * Makes a theme the default theme of the catalog version the list shows (contract ADR 0007): the
     * theme its templates that name none render with. An empty `themeId` clears it.
     */
    fun setCatalogDefault(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val segment = request.catalogSegment()
        val themeKey = request.params().getFirst("themeId")?.takeIf { it.isNotBlank() }?.let { ThemeKey.validateOrNull(it) ?: return ServerResponse.badRequest().build() }
        val themeCatalog = request.params().getFirst("themeCatalogKey")?.takeIf { it.isNotBlank() }?.let { CatalogKey.of(it) } ?: segment.key
        SetCatalogDefaultTheme(
            tenantKey = tenantId.key,
            catalogKey = segment.key,
            theme = themeKey?.let { CatalogDefaultTheme(it, themeCatalog) },
            catalogVersion = segment.ref(tenantId.key),
        ).execute()
        return request.htmx {
            fragment("themes/list", "rows") {
                "tenantId" to tenantId.key
                "tenant" to GetTenant(id = tenantId.key).query()
                "draftSegment" to segment
                "catalogDefault" to catalogDefaultOf(tenantId.key, segment)
                "themes" to ListThemes(tenantId = tenantId, catalogKey = segment.key, catalogVersion = segment.ref(tenantId.key)).query()
            }
            onFullPage { redirect(CatalogPages.list(request, tenantId.key, "themes")) }
        }
    }

    /**
     * Gives every catalog the tenant authors the house theme as its default theme (contract ADR 0007,
     * 1a), then returns to the list it was asked from, saying how many took it.
     */
    fun applyHouseTheme(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val result = ApplyHouseTheme(tenantId.key).execute()
        val back = request.param("catalog").orElse(null)?.let { "/tenants/${tenantId.key}/catalogs/$it/themes" } ?: "/tenants/${tenantId.key}/catalogs"
        return redirect("$back?houseThemeApplied=${result.applied.size}")
    }
}
