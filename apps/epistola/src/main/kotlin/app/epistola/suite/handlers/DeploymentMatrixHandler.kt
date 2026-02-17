package app.epistola.suite.templates

import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VersionId
import app.epistola.suite.environments.queries.ListEnvironments
import app.epistola.suite.htmx.htmx
import app.epistola.suite.htmx.redirect
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.commands.activations.RemoveActivation
import app.epistola.suite.templates.commands.versions.PublishToEnvironment
import app.epistola.suite.templates.queries.activations.GetDeploymentMatrix
import app.epistola.suite.templates.queries.variants.GetVariantSummaries
import app.epistola.suite.templates.queries.versions.ListPublishableVersionsByTemplate
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse

/**
 * Handles the deployment matrix view showing environment × variant activations.
 */
@Component
class DeploymentMatrixHandler {

    fun deploymentMatrix(request: ServerRequest): ServerResponse {
        val tenantId = TenantId.of(request.pathVariable("tenantId"))
        val templateIdStr = request.pathVariable("id")
        val templateId = TemplateId.validateOrNull(templateIdStr)
            ?: return ServerResponse.badRequest().build()

        return renderMatrix(request, tenantId, templateId)
    }

    fun updateDeployment(request: ServerRequest): ServerResponse {
        val tenantId = TenantId.of(request.pathVariable("tenantId"))
        val templateIdStr = request.pathVariable("id")
        val templateId = TemplateId.validateOrNull(templateIdStr)
            ?: return ServerResponse.badRequest().build()

        val variantIdStr = request.params().getFirst("variantId")
            ?: return ServerResponse.badRequest().build()
        val variantId = VariantId.validateOrNull(variantIdStr)
            ?: return ServerResponse.badRequest().build()

        val environmentIdStr = request.params().getFirst("environmentId")
            ?: return ServerResponse.badRequest().build()

        val versionIdStr = request.params().getFirst("versionId")

        if (versionIdStr.isNullOrBlank()) {
            RemoveActivation(
                tenantId = tenantId,
                templateId = templateId,
                variantId = variantId,
                environmentId = EnvironmentId.of(environmentIdStr),
            ).execute()
        } else {
            val versionIdInt = versionIdStr.toIntOrNull()
                ?: return ServerResponse.badRequest().build()

            if (versionIdInt !in VersionId.MIN_VERSION..VersionId.MAX_VERSION) {
                return ServerResponse.badRequest().build()
            }

            PublishToEnvironment(
                tenantId = tenantId,
                templateId = templateId,
                variantId = variantId,
                versionId = VersionId.of(versionIdInt),
                environmentId = EnvironmentId.of(environmentIdStr),
            ).execute()
        }

        return renderMatrix(request, tenantId, templateId)
    }

    private fun renderMatrix(
        request: ServerRequest,
        tenantId: TenantId,
        templateId: TemplateId,
    ): ServerResponse {
        val variants = GetVariantSummaries(tenantId = tenantId, templateId = templateId).query()
        val environments = ListEnvironments(tenantId = tenantId).query()
        val matrixCells = GetDeploymentMatrix(tenantId = tenantId, templateId = templateId).query()
        val publishableVersions = ListPublishableVersionsByTemplate(tenantId = tenantId, templateId = templateId).query()

        // Build lookups keyed by underlying String values, because Thymeleaf/SpringEL
        // unwraps @JvmInline value classes to their underlying types at runtime.
        val matrix = matrixCells.groupBy { it.variantId.value }
            .mapValues { (_, cells) -> cells.associateBy { it.environmentId.value } }

        // Build lookup: variantId -> list of publishable versions
        val versionsByVariant = publishableVersions.groupBy { it.variantId.value }

        return request.htmx {
            fragment("templates/deployment-matrix", "deployment-matrix") {
                "tenantId" to tenantId.value
                "templateId" to templateId
                "variants" to variants
                "environments" to environments
                "matrix" to matrix
                "versionsByVariant" to versionsByVariant
            }
            onNonHtmx { redirect("/tenants/${tenantId.value}/templates/$templateId") }
        }
    }
}
