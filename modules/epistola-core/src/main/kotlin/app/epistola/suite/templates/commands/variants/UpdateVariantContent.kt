// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.templates.commands.variants

import app.epistola.suite.catalog.DependencyScanner
import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.catalogAt
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
import app.epistola.suite.security.currentUserIdOrNull
import app.epistola.suite.templates.TemplateModels
import app.epistola.suite.templates.TemplateStore
import app.epistola.suite.templates.model.TemplateDocument
import app.epistola.suite.templates.services.TemplateDocumentPreparation
import app.epistola.suite.templates.templateIn
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
    private val templates: TemplateStore,
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
        val key = command.variantId.key.value
        val revision = jdbi.inTransaction<Long?, Exception> { handle ->
            // Its own content and revision (a row of its own, ADR 0033): the template and
            // its other variants are not written.
            templates.modifyVariant(handle, command.variantId, currentUserIdOrNull()?.value, command.expectedRevision, "Variant '$key'", headChange = false) { template ->
                template.variant(key) ?: return@modifyVariant null
                template.withVariant(key) { it.copy(model = templates.storedModel(prepared.templateModelJson)) }
            }?.second
        } ?: throw TemplateVariantNotFoundException(command.variantId.tenantKey, command.variantId.templateKey, command.variantId.key)
        return SavedVariantContent(TemplateModels.read(prepared.templateModelJson, command.variantId.catalogKey.value, objectMapper), revision)
    }
}

/** A variant's working model as it reads after the write, and the variant's revision. */
data class SavedVariantContent(val templateModel: TemplateDocument, val revision: Long)

/** The revision of [variantId] in the version the caller means: its own row's; null when there is no such variant. */
internal fun variantRevision(handle: Handle, variantId: VariantId): Long? = handle.createQuery(
    """
    SELECT revision FROM current_template_variants
    WHERE tenant_key = :t AND id = :v AND catalog_id = ${catalogAt("t", "c")} AND template_resource_id = ${templateIn("t", "c", "k")}
    """,
)
    .bind("t", variantId.tenantKey)
    .bindCatalog("c", variantId.catalogId)
    .bind("k", variantId.templateKey)
    .bind("v", variantId.key)
    .mapTo(Long::class.java)
    .findOne()
    .orElse(null)
