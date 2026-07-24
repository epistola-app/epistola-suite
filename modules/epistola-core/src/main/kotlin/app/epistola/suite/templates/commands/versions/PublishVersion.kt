// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.templates.commands.versions

import app.epistola.generation.pdf.RenderingDefaults
import app.epistola.suite.catalog.DependencyScanner
import app.epistola.suite.catalog.requireCatalogEditable
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.FontKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VersionId
import app.epistola.suite.fonts.queries.GetFontFamilyFingerprint
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
import org.jdbi.v3.core.Handle
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
                requireCatalogEditable(command.versionId.tenantKey, command.versionId.catalogKey)
                validateReferencedStencilsPublished(
                    handle,
                    command.versionId.tenantKey,
                    command.versionId.catalogKey.value,
                    version.templateModel,
                )
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

        val snapshot = ResolvedThemeSnapshot.from(resolvedStyles, effectiveThemeKey)
        return snapshot.copy(fontFingerprints = captureFontFingerprints(versionId, snapshot, template, tenant))
    }

    /**
     * Pins a per-family font fingerprint for every font family the published
     * snapshot references, so a later delete+re-upload of a face (different
     * bytes, same slug) is detected at render and fails loudly.
     *
     * Owning catalog for an *unqualified* font ref mirrors the render-path
     * cascade exactly (`DocumentGenerationExecutor` / `DocumentPreviewRenderer`):
     * the template's theme catalog → the tenant's default theme catalog → the
     * version's own catalog. Resilient: a blank slug or null fingerprint
     * (family has no faces) is skipped — never fails the publish.
     */
    private fun captureFontFingerprints(
        versionId: VersionId,
        snapshot: ResolvedThemeSnapshot,
        template: app.epistola.suite.templates.DocumentTemplate?,
        tenant: app.epistola.suite.tenants.Tenant?,
    ): Map<String, String> {
        @Suppress("UNCHECKED_CAST")
        val refs = DependencyScanner.themeFontRefs(
            documentStyles = snapshot.documentStyles,
            blockStylePresets = snapshot.blockStylePresets as Map<String, Any?>,
        )
        if (refs.isEmpty()) return emptyMap()

        val owningCatalogKey: CatalogKey =
            template?.themeCatalogKey ?: tenant?.defaultThemeCatalogKey ?: versionId.catalogKey

        return buildMap {
            for (ref in refs) {
                val slug = FontKey.validateOrNull(ref.slug) ?: continue
                val effCatalog = ref.catalogKey?.let(CatalogKey::of) ?: owningCatalogKey
                val fingerprint = runCatching {
                    GetFontFamilyFingerprint(versionId.tenantKey, effCatalog, slug).query()
                }.getOrNull() ?: continue
                put("${ref.catalogKey ?: ""}/${ref.slug}", fingerprint)
            }
        }
    }

    private data class StencilRef(val catalogKey: String, val stencilId: String, val pinnedVersion: Int?)

    private fun validateReferencedStencilsPublished(
        handle: Handle,
        tenantKey: TenantKey,
        defaultCatalogKey: String,
        templateModel: app.epistola.template.model.TemplateDocument,
    ) {
        val refs: Set<StencilRef> = templateModel.nodes.values
            .asSequence()
            .filter { it.type == "stencil" }
            .mapNotNull { node ->
                val props = node.props ?: return@mapNotNull null
                val stencilId = (props["stencilId"] as? String)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val refCatalogKey = (props["catalogKey"] as? String)?.takeIf { it.isNotBlank() } ?: defaultCatalogKey
                val pinned = (props["version"] as? Number)?.toInt()
                StencilRef(refCatalogKey, stencilId, pinned)
            }
            .toSet()

        if (refs.isEmpty()) return

        val unpublished = findUnpublishedStencilRefs(handle, tenantKey, refs)
        require(unpublished.isEmpty()) {
            val list = unpublished.joinToString(", ") { ref ->
                if (ref.pinnedVersion != null) "${ref.stencilId} v${ref.pinnedVersion}" else ref.stencilId
            }
            val noun = if (unpublished.size > 1) "stencils are" else "stencil is"
            "Cannot publish template version: referenced $noun not published — $list. Publish the stencil(s) first."
        }
    }

    private fun findUnpublishedStencilRefs(
        handle: Handle,
        tenantKey: TenantKey,
        refs: Set<StencilRef>,
    ): Set<StencilRef> {
        val unpublished = mutableSetOf<StencilRef>()
        refs.groupBy { it.catalogKey }.forEach { (catalogKey, group) ->
            val pinned = group.filter { it.pinnedVersion != null }
            val unpinned = group.filter { it.pinnedVersion == null }

            if (pinned.isNotEmpty()) {
                val publishedPairs: Set<Pair<String, Int>> = handle.createQuery(
                    """
                    SELECT stencil_key, id FROM stencil_versions
                    WHERE tenant_key = :tenantKey
                      AND catalog_key = :catalogKey
                      AND status = 'published'
                      AND stencil_key IN (<stencilIds>)
                    """,
                )
                    .bind("tenantKey", tenantKey)
                    .bind("catalogKey", catalogKey)
                    .bindList("stencilIds", pinned.map { it.stencilId }.distinct())
                    .map { rs, _ -> rs.getString("stencil_key") to rs.getInt("id") }
                    .set()
                for (ref in pinned) {
                    if ((ref.stencilId to ref.pinnedVersion!!) !in publishedPairs) {
                        unpublished.add(ref)
                    }
                }
            }

            if (unpinned.isNotEmpty()) {
                val publishedStencils: Set<String> = handle.createQuery(
                    """
                    SELECT DISTINCT stencil_key FROM stencil_versions
                    WHERE tenant_key = :tenantKey
                      AND catalog_key = :catalogKey
                      AND status = 'published'
                      AND stencil_key IN (<stencilIds>)
                    """,
                )
                    .bind("tenantKey", tenantKey)
                    .bind("catalogKey", catalogKey)
                    .bindList("stencilIds", unpinned.map { it.stencilId }.distinct())
                    .mapTo<String>()
                    .set()
                for (ref in unpinned) {
                    if (ref.stencilId !in publishedStencils) {
                        unpublished.add(ref)
                    }
                }
            }
        }
        return unpublished
    }
}
