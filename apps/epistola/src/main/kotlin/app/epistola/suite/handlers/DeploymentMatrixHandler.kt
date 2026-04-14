package app.epistola.suite.templates

import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.common.ids.VersionId
import app.epistola.suite.common.ids.VersionKey
import app.epistola.suite.environments.queries.ListEnvironments
import app.epistola.suite.htmx.catalogId
import app.epistola.suite.htmx.htmx
import app.epistola.suite.htmx.templateId
import app.epistola.suite.htmx.tenantId
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
        val tenantId = request.tenantId()
        val catalogId = request.catalogId()
        val templateId = request.templateId(tenantId)
            ?: return ServerResponse.badRequest().build()

        return renderMatrix(request, tenantId.key, catalogId, templateId.key, templateId)
    }

    fun updateDeployment(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        val catalogId = request.catalogId()
        val templateId = request.templateId(tenantId)
            ?: return ServerResponse.badRequest().build()

        val variantIdStr = request.params().getFirst("variantId")
            ?: return ServerResponse.badRequest().build()
        val variantKey = VariantKey.validateOrNull(variantIdStr)
            ?: return ServerResponse.badRequest().build()
        val variantId = VariantId(variantKey, templateId)

        val environmentIdStr = request.params().getFirst("environmentId")
            ?: return ServerResponse.badRequest().build()
        val environmentKey = EnvironmentKey.of(environmentIdStr)
        val environmentId = EnvironmentId(environmentKey, tenantId)

        val versionIdStr = request.params().getFirst("versionId")

        if (versionIdStr.isNullOrBlank()) {
            RemoveActivation(
                variantId = variantId,
                environmentId = environmentId,
            ).execute()
        } else {
            val versionIdInt = versionIdStr.toIntOrNull()
                ?: return ServerResponse.badRequest().build()

            if (versionIdInt !in VersionKey.MIN_VERSION..VersionKey.MAX_VERSION) {
                return ServerResponse.badRequest().build()
            }

            PublishToEnvironment(
                versionId = VersionId(VersionKey.of(versionIdInt), variantId),
                environmentId = environmentId,
            ).execute()
        }

        return renderMatrix(request, tenantId.key, catalogId, templateId.key, templateId)
    }

    private fun renderMatrix(
        request: ServerRequest,
        tenantKey: TenantKey,
        catalogId: CatalogKey,
        templateKey: TemplateKey,
        templateId: TemplateId,
    ): ServerResponse {
        val variants = GetVariantSummaries(templateId = templateId).query()
        val tenantId = TenantId(tenantKey)
        val environments = ListEnvironments(tenantId = tenantId).query()
        val matrixCells = GetDeploymentMatrix(templateId = templateId).query()
        val publishableVersions = ListPublishableVersionsByTemplate(templateId = templateId).query()

        // Build lookups keyed by underlying String values, because Thymeleaf/SpringEL
        // unwraps @JvmInline value classes to their underlying types at runtime.
        val matrix = matrixCells.groupBy { it.variantKey.value }
            .mapValues { (_, cells) -> cells.associateBy { it.environmentKey.value } }

        // Build lookup: variantId -> list of publishable versions
        val versionsByVariant = publishableVersions.groupBy { it.variantKey.value }

        return request.htmx {
            fragment("templates/deployment-matrix", "deployment-matrix") {
                "tenantId" to tenantKey.value
                "catalogId" to catalogId.value
                "templateId" to templateKey.value
                "variants" to variants
                "environments" to environments
                "matrix" to matrix
                "versionsByVariant" to versionsByVariant
            }
            onNonHtmx { redirect("/tenants/${tenantKey.value}/templates/$catalogId/${templateKey.value}") }
        }
    }
}
