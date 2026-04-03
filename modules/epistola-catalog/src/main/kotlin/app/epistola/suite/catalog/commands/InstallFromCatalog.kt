package app.epistola.suite.catalog.commands

import app.epistola.suite.catalog.CatalogClient
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.templates.commands.ImportStatus
import app.epistola.suite.templates.commands.ImportTemplateInput
import app.epistola.suite.templates.commands.ImportTemplateResult
import app.epistola.suite.templates.commands.ImportTemplates
import app.epistola.suite.templates.commands.ImportVariantInput
import app.epistola.suite.templates.model.DataExample
import org.jdbi.v3.core.Jdbi
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

data class InstallFromCatalog(
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
    val resourceSlugs: List<String>? = null,
) : Command<List<InstallResult>>,
    RequiresPermission {
    override val permission get() = Permission.TEMPLATE_EDIT
}

data class InstallResult(
    val slug: String,
    val status: InstallStatus,
    val errorMessage: String? = null,
)

enum class InstallStatus {
    INSTALLED,
    UPDATED,
    FAILED,
}

@Component
class InstallFromCatalogHandler(
    private val jdbi: Jdbi,
    private val catalogClient: CatalogClient,
) : CommandHandler<InstallFromCatalog, List<InstallResult>> {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun handle(command: InstallFromCatalog): List<InstallResult> {
        val catalog = GetCatalog(command.tenantKey, command.catalogKey).query()
            ?: throw IllegalArgumentException("Catalog not found: ${command.catalogKey}")

        val sourceUrl = catalog.sourceUrl
            ?: throw IllegalStateException("Catalog has no source URL: ${command.catalogKey}")

        val manifest = catalogClient.fetchManifest(sourceUrl, catalog.sourceAuthType, catalog.sourceAuthCredential)

        val resourcesToInstall = if (command.resourceSlugs != null) {
            manifest.resources.filter { it.slug in command.resourceSlugs }
        } else {
            manifest.resources
        }.filter { it.type == "template" }

        return resourcesToInstall.map { resource ->
            try {
                val detail = catalogClient.fetchResourceDetail(resource.detailUrl, sourceUrl, catalog.sourceAuthType, catalog.sourceAuthCredential)
                val importResult = importTemplate(command, detail.resource, manifest.release.version)
                registerCatalogTemplate(command, resource.slug)

                InstallResult(
                    slug = resource.slug,
                    status = if (importResult.status == ImportStatus.CREATED) InstallStatus.INSTALLED else InstallStatus.UPDATED,
                )
            } catch (e: Exception) {
                logger.error("Failed to install resource '${resource.slug}' from catalog '${command.catalogKey}': ${e.message}", e)
                InstallResult(
                    slug = resource.slug,
                    status = InstallStatus.FAILED,
                    errorMessage = e.message,
                )
            }
        }
    }

    private fun importTemplate(
        command: InstallFromCatalog,
        resource: app.epistola.suite.catalog.protocol.TemplateResource,
        releaseVersion: String,
    ): ImportTemplateResult {
        val input = ImportTemplateInput(
            slug = resource.slug,
            name = resource.name,
            version = releaseVersion,
            dataModel = resource.dataModel,
            dataExamples = resource.dataExamples?.map {
                DataExample(id = java.util.UUID.randomUUID().toString(), name = it.name, data = it.data)
            } ?: emptyList(),
            templateModel = resource.templateModel,
            variants = resource.variants.map { variant ->
                ImportVariantInput(
                    id = variant.id,
                    title = variant.title,
                    attributes = variant.attributes ?: emptyMap(),
                    templateModel = variant.templateModel,
                    isDefault = variant.isDefault,
                )
            },
            publishTo = emptyList(),
        )

        val results = ImportTemplates(
            tenantId = TenantId(command.tenantKey),
            templates = listOf(input),
        ).execute()

        val result = results.first()
        if (result.status == ImportStatus.FAILED) {
            throw RuntimeException("Import failed for '${resource.slug}': ${result.errorMessage}")
        }
        return result
    }

    private fun registerCatalogTemplate(command: InstallFromCatalog, resourceSlug: String) {
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                INSERT INTO catalog_templates (tenant_key, catalog_key, template_key, catalog_resource_slug)
                VALUES (:tenantKey, :catalogKey, :templateKey, :resourceSlug)
                ON CONFLICT (tenant_key, template_key) DO UPDATE
                SET catalog_key = :catalogKey, catalog_resource_slug = :resourceSlug
                """,
            )
                .bind("tenantKey", command.tenantKey)
                .bind("catalogKey", command.catalogKey)
                .bind("templateKey", TemplateKey.of(resourceSlug))
                .bind("resourceSlug", resourceSlug)
                .execute()
        }
    }
}
