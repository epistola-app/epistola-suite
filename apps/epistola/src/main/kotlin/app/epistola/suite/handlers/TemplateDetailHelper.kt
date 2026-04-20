package app.epistola.suite.templates

import app.epistola.suite.catalog.CatalogType
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.htmx.catalogId
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
        val templateId = request.templateId(tenantId) ?: return null

        val template = GetDocumentTemplate(id = templateId).query() ?: return null
        val editable = template.catalogType == CatalogType.AUTHORED

        return TemplateDetailContext(
            tenantId = tenantId.key.value,
            catalogId = catalogId.value,
            templateId = templateId,
            template = template,
            editable = editable,
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
            "template" to ctx.template,
            "editable" to ctx.editable,
            "activeTab" to activeTab,
            "contentView" to "templates/detail",
        )
        model.putAll(tabModel)
        return ServerResponse.ok().render("layout/shell", model)
    }
}
