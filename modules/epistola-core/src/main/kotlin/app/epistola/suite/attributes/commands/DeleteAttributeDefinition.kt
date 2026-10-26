// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.attributes.commands

import app.epistola.suite.attributes.AttributeReferences
import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.inCurrentVersion
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.CatalogReleaseScoped
import app.epistola.suite.catalog.requireCatalogEditable
import app.epistola.suite.catalog.versionAt
import app.epistola.suite.common.ids.AttributeId
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.templates.currentTemplateViewJoin
import app.epistola.suite.templates.templateJoin
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/**
 * Thrown when attempting to delete an attribute definition that is still referenced by variants.
 */
class AttributeInUseException(
    val attributeId: AttributeId,
    val variantCount: Long,
) : RuntimeException(
    "Cannot delete attribute '${attributeId.key.value}': it is still referenced by $variantCount variant(s). " +
        "Remove the attribute from all variants first.",
)

data class DeleteAttributeDefinition(
    val id: AttributeId,
    /** The catalog version this addresses; null for its catalog's current version. */
    override val catalogVersion: CatalogReleaseRef? = null,
) : CatalogReleaseScoped,
    Command<Boolean>,
    RequiresPermission {
    override val permission get() = Permission.REFERENCE_EDIT
    override val tenantKey get() = id.tenantKey
}

@Component
class DeleteAttributeDefinitionHandler(
    private val jdbi: Jdbi,
    private val attributes: app.epistola.suite.attributes.AttributeStore,
) : CommandHandler<DeleteAttributeDefinition, Boolean> {
    override fun handle(command: DeleteAttributeDefinition): Boolean {
        requireCatalogEditable(command.id.catalogId)
        return jdbi.withHandle<Boolean, Exception> { handle ->
            // Any variant, in any catalog, that still names this attribute (qualified or bare)
            val variantCount = handle.createQuery(
                """
                SELECT COUNT(*) FROM current_template_variants variants
                ${currentTemplateViewJoin("variants")}
                WHERE variants.tenant_key = :tenantId
                  AND ${AttributeReferences.uses("variants")}
                """,
            )
                .bind("tenantId", command.id.tenantKey)
                .bind(AttributeReferences.QUALIFIED_PARAM, AttributeReferences.qualifiedKey(command.id))
                .bind(AttributeReferences.BARE_PARAM, AttributeReferences.bareKey(command.id))
                .mapTo(Long::class.java)
                .one()

            if (variantCount > 0) {
                throw AttributeInUseException(command.id, variantCount)
            }

            attributes.delete(handle, command.id)
        }
    }
}
