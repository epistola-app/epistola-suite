package app.epistola.suite.templates.commands.versions

import app.epistola.generation.pdf.RenderingDefaults
import app.epistola.suite.catalog.requireCatalogEditable
import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VersionId
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.templates.model.ContractVersion
import app.epistola.suite.templates.model.EnvironmentActivation
import app.epistola.suite.templates.model.TemplateVersion
import app.epistola.suite.templates.queries.GetDocumentTemplate
import app.epistola.suite.templates.validation.SchemaCompatibilityChecker
import app.epistola.suite.tenants.queries.GetTenant
import app.epistola.suite.themes.ResolvedThemeSnapshot
import app.epistola.suite.themes.ThemeStyleResolver
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * Publishes a version to an environment in a single action.
 *
 * If the version is a draft, it freezes the content (status -> published).
 * If already published, this is a no-op on the version itself.
 * Archived versions cannot be published.
 *
 * Then creates/updates the activation for the variant in the target environment.
 *
 * Returns the result, or null if:
 * - The version doesn't exist or is archived
 * - The environment doesn't belong to the tenant
 */
data class PublishToEnvironment(
    val versionId: VersionId,
    val environmentId: EnvironmentId,
) : Command<PublishToEnvironmentResult?>,
    RequiresPermission {
    override val permission = Permission.TEMPLATE_PUBLISH
    override val tenantKey: TenantKey get() = versionId.tenantKey
}

data class PublishToEnvironmentResult(
    val version: TemplateVersion,
    val activation: EnvironmentActivation,
)

@Component
class PublishToEnvironmentHandler(
    private val jdbi: Jdbi,
    private val themeStyleResolver: ThemeStyleResolver,
    private val mediator: Mediator,
    private val objectMapper: ObjectMapper,
    private val compatibilityChecker: SchemaCompatibilityChecker,
) : CommandHandler<PublishToEnvironment, PublishToEnvironmentResult?> {
    override fun handle(command: PublishToEnvironment): PublishToEnvironmentResult? {
        requireCatalogEditable(command.versionId.tenantKey, command.versionId.catalogKey)
        return jdbi.inTransaction<PublishToEnvironmentResult?, Exception> { handle ->
            // 1. Verify environment belongs to tenant
            val environmentExists = handle.createQuery(
                """
                SELECT COUNT(*) > 0
                FROM environments
                WHERE id = :environmentId AND tenant_key = :tenantId
                """,
            )
                .bind("environmentId", command.environmentId.key)
                .bind("tenantId", command.environmentId.tenantKey)
                .mapTo<Boolean>()
                .one()

            if (!environmentExists) {
                return@inTransaction null
            }

            // 2. Fetch the version
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

            // 3. Archived versions cannot be published
            if (version.status.name == "ARCHIVED") {
                return@inTransaction null
            }

            // 3b. Auto-publish contract if it's a draft
            if (version.contractVersion != null) {
                val contract = handle.createQuery(
                    """
                    SELECT id, tenant_key, catalog_key, template_key, schema, data_model, data_examples,
                           status, created_at, published_at, created_by
                    FROM contract_versions
                    WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey
                      AND template_key = :templateKey AND id = :contractVersion
                    """,
                )
                    .bind("tenantKey", command.versionId.tenantKey)
                    .bind("catalogKey", command.versionId.catalogKey)
                    .bind("templateKey", command.versionId.templateKey)
                    .bind("contractVersion", version.contractVersion.value)
                    .mapTo<ContractVersion>()
                    .findOne()
                    .orElse(null)

                if (contract != null && contract.status.name == "DRAFT") {
                    // Check compatibility against previous published contract
                    val previousPublished = handle.createQuery(
                        """
                        SELECT id, tenant_key, catalog_key, template_key, schema, data_model, data_examples,
                               status, created_at, published_at, created_by
                        FROM contract_versions
                        WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey
                          AND template_key = :templateKey AND status = 'published'
                        ORDER BY id DESC LIMIT 1
                        """,
                    )
                        .bind("tenantKey", command.versionId.tenantKey)
                        .bind("catalogKey", command.versionId.catalogKey)
                        .bind("templateKey", command.versionId.templateKey)
                        .mapTo<ContractVersion>()
                        .findOne()
                        .orElse(null)

                    val compatibilityResult = if (previousPublished != null) {
                        compatibilityChecker.checkCompatibility(previousPublished.dataModel, contract.dataModel)
                    } else {
                        SchemaCompatibilityChecker.CompatibilityResult(compatible = true, breakingChanges = emptyList())
                    }

                    require(compatibilityResult.compatible) {
                        "Cannot auto-publish contract version ${version.contractVersion.value}: breaking changes detected. " +
                            "Publish the contract separately first. Breaking changes: " +
                            compatibilityResult.breakingChanges.joinToString { it.description }
                    }

                    // Auto-publish the contract
                    handle.createUpdate(
                        """
                        UPDATE contract_versions SET status = 'published', published_at = NOW()
                        WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey
                          AND template_key = :templateKey AND id = :contractVersion
                        """,
                    )
                        .bind("tenantKey", command.versionId.tenantKey)
                        .bind("catalogKey", command.versionId.catalogKey)
                        .bind("templateKey", command.versionId.templateKey)
                        .bind("contractVersion", version.contractVersion.value)
                        .execute()

                    // Auto-upgrade other template versions from previous contract version to new
                    if (previousPublished != null) {
                        handle.createUpdate(
                            """
                            UPDATE template_versions
                            SET contract_version = :newVersion
                            WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey
                              AND template_key = :templateKey AND contract_version = :oldVersion
                            """,
                        )
                            .bind("tenantKey", command.versionId.tenantKey)
                            .bind("catalogKey", command.versionId.catalogKey)
                            .bind("templateKey", command.versionId.templateKey)
                            .bind("newVersion", version.contractVersion.value)
                            .bind("oldVersion", previousPublished.id.value)
                            .execute()
                    }
                }
            }

            // 4. If draft, freeze it (update to published) with rendering snapshot
            val wasDraft = version.status.name == "DRAFT"
            if (wasDraft) {
                // Resolve theme snapshot for deterministic rendering
                val themeSnapshot = resolveThemeSnapshot(command, version)
                val themeSnapshotJson = themeSnapshot?.let {
                    objectMapper.writeValueAsString(it)
                }

                handle.createUpdate(
                    """
                    UPDATE template_versions
                    SET status = 'published',
                        published_at = NOW(),
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
            // If already published, no-op on version (idempotent)

            // 5. Upsert activation
            val activation = handle.createQuery(
                """
                INSERT INTO environment_activations (tenant_key, catalog_key, environment_key, template_key, variant_key, version_key, activated_at)
                VALUES (:tenantId, :catalogKey, :environmentId, :templateId, :variantId, :versionId, NOW())
                ON CONFLICT (tenant_key, catalog_key, environment_key, template_key, variant_key)
                DO UPDATE SET version_key = :versionId, activated_at = NOW()
                RETURNING *
                """,
            )
                .bind("tenantId", command.versionId.tenantKey)
                .bind("catalogKey", command.versionId.catalogKey)
                .bind("environmentId", command.environmentId.key)
                .bind("templateId", command.versionId.templateKey)
                .bind("variantId", command.versionId.variantKey)
                .bind("versionId", command.versionId.key)
                .mapTo<EnvironmentActivation>()
                .one()

            // 6. Re-fetch version to get updated state
            val updatedVersion = handle.createQuery(
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
                .one()

            PublishToEnvironmentResult(
                version = updatedVersion,
                activation = activation,
            )
        }
    }

    /**
     * Resolves the full theme cascade and creates a snapshot for the given version.
     */
    private fun resolveThemeSnapshot(command: PublishToEnvironment, version: TemplateVersion): ResolvedThemeSnapshot? {
        val templateId = TemplateId(command.versionId.templateKey, command.versionId.catalogId)

        // Get template-level default theme
        val template = mediator.query(GetDocumentTemplate(templateId))
        val templateDefaultThemeKey = template?.themeKey

        // Get tenant-level default theme
        val tenant = mediator.query(GetTenant(id = command.versionId.tenantKey))
        val tenantDefaultThemeKey = tenant?.defaultThemeKey

        // Resolve the full theme cascade
        val resolvedStyles = themeStyleResolver.resolveStyles(
            command.versionId.tenantKey,
            templateDefaultThemeKey,
            tenantDefaultThemeKey,
            version.templateModel,
        )

        // Determine which theme key was actually used
        val effectiveThemeKey = when (val ref = version.templateModel.themeRef) {
            is app.epistola.template.model.ThemeRefOverride ->
                app.epistola.suite.common.ids.ThemeKey.of(ref.themeId)
            else -> null
        } ?: templateDefaultThemeKey ?: tenantDefaultThemeKey

        return ResolvedThemeSnapshot.from(resolvedStyles, effectiveThemeKey)
    }
}
