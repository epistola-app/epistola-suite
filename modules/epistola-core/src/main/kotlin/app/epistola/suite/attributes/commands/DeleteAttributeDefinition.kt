// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.attributes.commands

import app.epistola.suite.attributes.AttributeReferences
import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.bindVersion
import app.epistola.suite.catalog.inVersionOrNext
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.CatalogReleaseScoped
import app.epistola.suite.catalog.releases.addressedVersion
import app.epistola.suite.catalog.releases.versionOf
import app.epistola.suite.catalog.requireCatalogEditable
import app.epistola.suite.catalog.templateViewJoin
import app.epistola.suite.common.ids.AttributeId
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
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
    /** The catalog version this addresses; null for its catalog's next version. */
    override val catalogVersion: CatalogReleaseRef?,
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
            val at = handle.versionOf(command.id.catalogId, command)
            // Any variant, in any catalog, that still names this attribute (qualified or bare):
            // of this version for its catalog, else of each one's next version.
            val variantCount = handle.createQuery(
                """
                SELECT COUNT(*) FROM version_template_variants variants
                ${templateViewJoin("variants")}
                WHERE variants.tenant_key = :tenantId
                  AND ${inVersionOrNext("variants", "version")}
                  AND ${AttributeReferences.uses("variants")}
                """,
            )
                .bind("tenantId", command.id.tenantKey)
                .bind(AttributeReferences.QUALIFIED_PARAM, AttributeReferences.qualifiedKey(command.id))
                .bind(AttributeReferences.BARE_PARAM, AttributeReferences.bareKey(command.id))
                .bindVersion("version", handle.addressedVersion(command))
                .mapTo(Long::class.java)
                .one()

            if (variantCount > 0) {
                throw AttributeInUseException(command.id, variantCount)
            }

            attributes.delete(handle, at, command.id)
        }
    }
}
