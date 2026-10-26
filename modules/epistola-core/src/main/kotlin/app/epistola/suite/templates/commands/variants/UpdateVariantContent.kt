// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.templates.commands.variants

import app.epistola.suite.catalog.DependencyScanner
import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.dependencies.UsedCatalogDeclarer
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.CatalogReleaseScoped
import app.epistola.suite.catalog.releases.PresetUse
import app.epistola.suite.catalog.requireCatalogEditable
import app.epistola.suite.catalog.versionAt
import app.epistola.suite.common.ResourceModifiedException
import app.epistola.suite.common.Revisions
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.documents.TemplateVariantNotFoundException
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.templates.TemplateModels
import app.epistola.suite.templates.model.TemplateDocument
import app.epistola.suite.templates.services.TemplateDocumentPreparation
import app.epistola.suite.templates.templateAtAddress
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * Replaces the working model of a variant (contract ADR 0002: the catalog release is the only
 * version, so a variant has one model to edit). The model is validated and prepared as every write
 * is, stencil instances stored by reference. An edit makes a ready mark on the variant stale.
 *
 * With [expectedRevision] it is refused ([ResourceModifiedException]) when the variant changed since
 * that revision was read, and nothing is written.
 *
 * @return the model as stored, and the variant's revision now.
 */
data class UpdateVariantContent(
    val variantId: VariantId,
    val templateModel: TemplateDocument,
    /** The catalog version this addresses; null for its catalog's current version. */
    override val catalogVersion: CatalogReleaseRef? = null,
    /** The variant's revision the change was made against; null to write whatever it is at now. */
    val expectedRevision: Long? = null,
) : CatalogReleaseScoped,
    Command<SavedVariantContent>,
    RequiresPermission {
    override val permission = Permission.TEMPLATE_EDIT
    override val tenantKey: TenantKey get() = variantId.tenantKey
}

@Component
class UpdateVariantContentHandler(
    private val jdbi: Jdbi,
    private val templateDocumentPreparation: TemplateDocumentPreparation,
    private val objectMapper: ObjectMapper,
    private val usedCatalogs: UsedCatalogDeclarer,
) : CommandHandler<UpdateVariantContent, SavedVariantContent> {
    override fun handle(command: UpdateVariantContent): SavedVariantContent {
        val saved = save(command)
        // What the saved resource uses of other catalogs is declared now, so the draft renders and releases with it.
        usedCatalogs.declare(command.variantId.catalogId, DependencyScanner.crossCatalogRefs(command.templateModel, command.variantId.catalogKey.value))
        return saved
    }

    private fun save(command: UpdateVariantContent): SavedVariantContent {
        requireCatalogEditable(command.variantId.catalogId)
        PresetUse.requireAvailable(command.variantId.catalogId, command.templateModel)
        val prepared = templateDocumentPreparation.prepareDraft(command.templateModel, command.variantId.catalogKey)
        val revision = jdbi.withHandle<Long?, Exception> { handle ->
            val written = handle.createQuery(
                """
                UPDATE template_variants
                SET template_model = :templateModel::jsonb, referenced_paths = :referencedPaths::jsonb, updated_at = NOW()
                WHERE tenant_key = :tenantKey AND id = :variantKey
                  AND version_id = ${versionAt("tenantKey", "catalogKey")} AND template_resource_id = ${templateAtAddress("tenantKey", "catalogKey", "templateKey")}
                  AND ${Revisions.MATCHES}
                RETURNING revision
                """,
            )
                .bind("tenantKey", command.variantId.tenantKey)
                .bindCatalog("catalogKey", command.variantId.catalogId)
                .bind("templateKey", command.variantId.templateKey)
                .bind("variantKey", command.variantId.key)
                .bind("templateModel", prepared.templateModelJson)
                .bind("referencedPaths", prepared.referencedPathsJson)
                .bind("expectedRevision", command.expectedRevision)
                .mapTo(Long::class.java)
                .findOne()
                .orElse(null)
            if (written == null) Revisions.refuse("Variant '${command.variantId.key.value}'", command.expectedRevision, variantRevision(handle, command.variantId))
            written
        } ?: throw TemplateVariantNotFoundException(command.variantId.tenantKey, command.variantId.templateKey, command.variantId.key)
        return SavedVariantContent(TemplateModels.read(prepared.templateModelJson, command.variantId.catalogKey.value, objectMapper), revision)
    }
}

/** A variant's working model as it reads after the write, and the variant's revision. */
data class SavedVariantContent(val templateModel: TemplateDocument, val revision: Long)

/** The revision of [variantId] in the version the caller means, or null when there is no such variant. */
internal fun variantRevision(handle: Handle, variantId: VariantId): Long? = handle.createQuery(
    """
    SELECT revision FROM template_variants
    WHERE tenant_key = :t AND id = :v AND version_id = ${versionAt("t", "c")} AND template_resource_id = ${templateAtAddress("t", "c", "k")}
    """,
)
    .bind("t", variantId.tenantKey)
    .bindCatalog("c", variantId.catalogId)
    .bind("k", variantId.templateKey)
    .bind("v", variantId.key)
    .mapTo(Long::class.java)
    .findOne()
    .orElse(null)
