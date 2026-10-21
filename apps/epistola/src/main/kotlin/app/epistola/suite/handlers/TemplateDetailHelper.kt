// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.templates

import app.epistola.suite.catalog.versions.CatalogVersionRef
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.handlers.CatalogDraftBar
import app.epistola.suite.htmx.catalogId
import app.epistola.suite.htmx.catalogSegment
import app.epistola.suite.htmx.templateId
import app.epistola.suite.htmx.tenantId
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.queries.GetDocumentTemplate
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse

/**
 * Shared data for all template detail tabs.
 */
data class TemplateDetailContext(
    val tenantId: String,
    val catalogId: String,
    val templateId: TemplateId,
    val template: DocumentTemplate,
    val editable: Boolean,
    /** The route's catalog segment (`letters@2.0.0` for a draft), which the page's links carry. */
    val catalogSegment: String = catalogId,
    /** The draft the page works in, for every message it dispatches; null for the current version. */
    val catalogVersion: CatalogVersionRef? = null,
    /** Which draft this is and the others to switch to. */
    val draftBar: CatalogDraftBar? = null,
)

/**
 * Helper for rendering template detail pages with a specific active tab.
 * Each tab handler loads its own data and calls [renderDetailPage].
 */
@Component
class TemplateDetailHelper {

    /**
     * Loads the shared template context from the request path variables.
     * Returns null if the template is not found.
     */
    fun loadContext(request: ServerRequest): TemplateDetailContext? {
        val tenantId = request.tenantId()
        val catalogId = request.catalogId()
        val segment = request.catalogSegment()
        val templateId = request.templateId(tenantId) ?: return null
        // Resolved first: a route naming a draft the catalog does not have is refused here.
        val draftBar = CatalogDraftBar.of(tenantId.key, segment, CatalogDraftBar.inPath(request.path(), segment))
        val version = segment.ref(tenantId.key)

        val template = GetDocumentTemplate(id = templateId, catalogVersion = version).query() ?: return null
        val editable = draftBar.editable

        return TemplateDetailContext(
            tenantId = tenantId.key.value,
            catalogId = catalogId.value,
            templateId = templateId,
            template = template,
            editable = editable,
            catalogSegment = segment.value,
            catalogVersion = version,
            draftBar = draftBar,
        )
    }

    /**
     * Renders the full detail page with the given active tab and tab-specific model data.
     */
    fun renderDetailPage(
        ctx: TemplateDetailContext,
        activeTab: String,
        tabModel: Map<String, Any?> = emptyMap(),
    ): ServerResponse {
        val model = mutableMapOf<String, Any?>(
            "pageTitle" to "${ctx.template.name} - Epistola",
            "tenantId" to ctx.tenantId,
            "catalogId" to ctx.catalogId,
            "catalogSegment" to ctx.catalogSegment,
            "draftBar" to ctx.draftBar,
            "template" to ctx.template,
            "editable" to ctx.editable,
            "activeTab" to activeTab,
            "contentView" to "templates/detail",
        )
        model.putAll(tabModel)
        return ServerResponse.ok().render("layout/shell", model)
    }
}
