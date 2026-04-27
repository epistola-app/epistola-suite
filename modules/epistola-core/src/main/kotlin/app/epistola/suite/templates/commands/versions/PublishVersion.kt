package app.epistola.suite.templates.commands.versions

import app.epistola.generation.pdf.RenderingDefaults
import app.epistola.suite.catalog.requireCatalogEditable
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VersionId
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.templates.model.TemplateVersion
import app.epistola.suite.templates.queries.GetDocumentTemplate
import app.epistola.suite.tenants.queries.GetTenant
import app.epistola.suite.themes.ResolvedThemeSnapshot
import app.epistola.suite.themes.ThemeStyleResolver
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * Publishes a draft template version (freezes it with theme snapshot and rendering defaults).
 * Does NOT create an environment activation — use PublishToEnvironment for that.
 *
 * Also auto-publishes compatible contract drafts, blocks on breaking contract changes.
 *
 * Returns null if version doesn't exist or is archived.
 * Idempotent: if already published, returns the published version.
 */
data class PublishVersion(
    val versionId: VersionId,
) : Command<TemplateVersion?>,
    RequiresPermission {
    override val permission = Permission.TEMPLATE_PUBLISH
    override val tenantKey: TenantKey get() = versionId.tenantKey
}

@Component
class PublishVersionHandler(
    private val jdbi: Jdbi,
    private val themeStyleResolver: ThemeStyleResolver,
    private val mediator: Mediator,
    private val objectMapper: ObjectMapper,
) : CommandHandler<PublishVersion, TemplateVersion?> {
    override fun handle(command: PublishVersion): TemplateVersion? {
        requireCatalogEditable(command.versionId.tenantKey, command.versionId.catalogKey)
        return jdbi.inTransaction<TemplateVersion?, Exception> { handle ->
            // 1. Fetch the version
            val version = handle.createQuery(
                """
                SELECT *
                FROM template_versions
                WHERE tenant_key = :tenantId AND catalog_key = :catalogKey AND template_key = :templateId AND variant_key = :variantId AND id = :versionId
                """,
            )
                .bind("tenantId", command.versionId.tenantKey)
                .bind("catalogKey", command.versionId.catalogKey)
                .bind("templateId", command.versionId.templateKey)
                .bind("variantId", command.versionId.variantKey)
                .bind("versionId", command.versionId.key)
                .mapTo<TemplateVersion>()
                .findOne()
                .orElse(null) ?: return@inTransaction null

            // 2. Archived versions cannot be published
            if (version.status.name == "ARCHIVED") {
                return@inTransaction null
            }

            // 3. Auto-publish compatible contract drafts, block on breaking changes
            if (version.contractVersion != null) {
                val contractStatus = handle.createQuery(
                    "SELECT status FROM contract_versions WHERE tenant_key = :tk AND catalog_key = :ck AND template_key = :tpk AND id = :cv",
                )
                    .bind("tk", command.versionId.tenantKey)
                    .bind("ck", command.versionId.catalogKey)
                    .bind("tpk", command.versionId.templateKey)
                    .bind("cv", version.contractVersion.value)
                    .mapTo<String>()
                    .findOne()
                    .orElse(null)

                if (contractStatus == "draft") {
                    val templateId = TemplateId(command.versionId.templateKey, command.versionId.catalogId)
                    val publishResult = app.epistola.suite.templates.contracts.commands.PublishContractVersion(
                        templateId = templateId,
                        confirmed = true,
                    ).execute()

                    require(publishResult != null && publishResult.compatible) {
                        "Cannot publish template version: contract has breaking changes. Publish the contract explicitly first. " +
                            "Breaking changes: ${publishResult?.breakingChanges?.joinToString { it.description } ?: ""}"
                    }
                }
            }

            // 4. If draft, freeze it (update to published) with rendering snapshot
            if (version.status.name == "DRAFT") {
                val themeSnapshot = resolveThemeSnapshot(command.versionId, version)
                val themeSnapshotJson = themeSnapshot?.let { objectMapper.writeValueAsString(it) }

                handle.createUpdate(
                    """
                    UPDATE template_versions
                    SET status = 'published', published_at = NOW(),
                        rendering_defaults_version = :renderingDefaultsVersion,
                        resolved_theme = CAST(:resolvedTheme AS JSONB)
                    WHERE tenant_key = :tenantId AND catalog_key = :catalogKey AND template_key = :templateId AND variant_key = :variantId AND id = :versionId
                    """,
                )
                    .bind("tenantId", command.versionId.tenantKey)
                    .bind("catalogKey", command.versionId.catalogKey)
                    .bind("templateId", command.versionId.templateKey)
                    .bind("variantId", command.versionId.variantKey)
                    .bind("versionId", command.versionId.key)
                    .bind("renderingDefaultsVersion", RenderingDefaults.CURRENT.version)
                    .bind("resolvedTheme", themeSnapshotJson)
                    .execute()
            }

            // 5. Re-fetch and return
            handle.createQuery(
                """
                SELECT * FROM template_versions
                WHERE tenant_key = :tenantId AND catalog_key = :catalogKey AND template_key = :templateId AND variant_key = :variantId AND id = :versionId
                """,
            )
                .bind("tenantId", command.versionId.tenantKey)
                .bind("catalogKey", command.versionId.catalogKey)
                .bind("templateId", command.versionId.templateKey)
                .bind("variantId", command.versionId.variantKey)
                .bind("versionId", command.versionId.key)
                .mapTo<TemplateVersion>()
                .one()
        }
    }

    private fun resolveThemeSnapshot(versionId: VersionId, version: TemplateVersion): ResolvedThemeSnapshot? {
        val templateId = TemplateId(versionId.templateKey, versionId.catalogId)
        val template = mediator.query(GetDocumentTemplate(templateId))
        val tenant = mediator.query(GetTenant(id = versionId.tenantKey))

        val resolvedStyles = themeStyleResolver.resolveStyles(
            versionId.tenantKey,
            template?.themeKey,
            tenant?.defaultThemeKey,
            version.templateModel,
            templateCatalogKey = template?.themeCatalogKey,
            tenantDefaultThemeCatalogKey = tenant?.defaultThemeCatalogKey,
        )

        val effectiveThemeKey = when (val ref = version.templateModel.themeRef) {
            is app.epistola.template.model.ThemeRefOverride ->
                app.epistola.suite.common.ids.ThemeKey.of(ref.themeId)
            else -> null
        } ?: template?.themeKey ?: tenant?.defaultThemeKey

        return ResolvedThemeSnapshot.from(resolvedStyles, effectiveThemeKey)
    }
}
