package app.epistola.suite.templates

import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VersionId
import app.epistola.suite.htmx.htmx
import app.epistola.suite.htmx.redirect
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.commands.versions.ArchiveVersion
import app.epistola.suite.templates.commands.versions.CreateVersion
import app.epistola.suite.templates.commands.versions.PublishVersion
import app.epistola.suite.templates.model.VariantSummary
import app.epistola.suite.templates.queries.GetDocumentTemplate
import app.epistola.suite.templates.queries.variants.GetVariant
import app.epistola.suite.templates.queries.versions.ListVersions
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse

/**
 * Handles version lifecycle routes for document templates.
 * Manages draft creation, publishing, and archiving of template versions.
 */
@Component
class VersionRouteHandler {

    fun createDraft(request: ServerRequest): ServerResponse {
        val tenantId = request.pathVariable("tenantId")
        val templateIdStr = request.pathVariable("id")
        val templateId = TemplateId.validateOrNull(templateIdStr)
            ?: return ServerResponse.badRequest().build()
        val variantIdStr = request.pathVariable("variantId")
        val variantId = VariantId.validateOrNull(variantIdStr)
            ?: return ServerResponse.badRequest().build()

        CreateVersion(
            tenantId = TenantId.of(tenantId),
            templateId = templateId,
            variantId = variantId,
        ).execute()

        return returnVersionsFragment(request, tenantId, templateId, variantId)
    }

    fun publishVersion(request: ServerRequest): ServerResponse {
        val tenantId = request.pathVariable("tenantId")
        val templateIdStr = request.pathVariable("id")
        val templateId = TemplateId.validateOrNull(templateIdStr)
            ?: return ServerResponse.badRequest().build()
        val variantIdStr = request.pathVariable("variantId")
        val variantId = VariantId.validateOrNull(variantIdStr)
            ?: return ServerResponse.badRequest().build()
        val versionIdInt = request.pathVariable("versionId").toIntOrNull()
            ?: return ServerResponse.badRequest().build()

        if (versionIdInt !in VersionId.MIN_VERSION..VersionId.MAX_VERSION) {
            return ServerResponse.badRequest().build()
        }

        PublishVersion(
            tenantId = TenantId.of(tenantId),
            templateId = templateId,
            variantId = variantId,
            versionId = VersionId.of(versionIdInt),
        ).execute()

        return returnVersionsFragment(request, tenantId, templateId, variantId)
    }

    fun archiveVersion(request: ServerRequest): ServerResponse {
        val tenantId = request.pathVariable("tenantId")
        val templateIdStr = request.pathVariable("id")
        val templateId = TemplateId.validateOrNull(templateIdStr)
            ?: return ServerResponse.badRequest().build()
        val variantIdStr = request.pathVariable("variantId")
        val variantId = VariantId.validateOrNull(variantIdStr)
            ?: return ServerResponse.badRequest().build()
        val versionIdInt = request.pathVariable("versionId").toIntOrNull()
            ?: return ServerResponse.badRequest().build()

        if (versionIdInt !in VersionId.MIN_VERSION..VersionId.MAX_VERSION) {
            return ServerResponse.badRequest().build()
        }

        ArchiveVersion(
            tenantId = TenantId.of(tenantId),
            templateId = templateId,
            variantId = variantId,
            versionId = VersionId.of(versionIdInt),
        ).execute()

        return returnVersionsFragment(request, tenantId, templateId, variantId)
    }

    private fun returnVersionsFragment(
        request: ServerRequest,
        tenantId: String,
        templateId: TemplateId,
        variantId: VariantId,
    ): ServerResponse {
        val template = GetDocumentTemplate(
            tenantId = TenantId.of(tenantId),
            id = templateId,
        ).query() ?: return ServerResponse.notFound().build()

        val variant = GetVariant(
            tenantId = TenantId.of(tenantId),
            templateId = templateId,
            variantId = variantId,
        ).query() ?: return ServerResponse.notFound().build()

        val versions = ListVersions(
            tenantId = TenantId.of(tenantId),
            templateId = templateId,
            variantId = variantId,
        ).query()

        val selectedVariantSummary = VariantSummary(
            id = variant.id,
            title = variant.title,
            tags = variant.tags,
            hasDraft = versions.any { it.status.name == "DRAFT" },
            publishedVersions = versions.filter { it.status.name == "PUBLISHED" }.map { it.id.value }.sorted(),
        )

        return request.htmx {
            fragment("templates/detail", "versions-section") {
                "tenantId" to tenantId
                "template" to template
                "selectedVariant" to selectedVariantSummary
                "versions" to versions
            }
            onNonHtmx { redirect("/tenants/$tenantId/templates/$templateId/variants/$variantId") }
        }
    }
}
