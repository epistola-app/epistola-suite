// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.attributes.codelists.commands

import app.epistola.suite.attributes.codelists.CODE_LIST_AT_ADDRESS
import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.inCurrentVersion
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.CatalogReleaseScoped
import app.epistola.suite.catalog.requireCatalogEditable
import app.epistola.suite.catalog.versionAt
import app.epistola.suite.common.ids.CodeListId
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/**
 * Thrown when a code list cannot be deleted because attributes still bind to it.
 *
 * The DB-level FK from `variant_attribute_definitions.(tenant, catalog, slug)`
 * to `code_lists` uses `ON DELETE RESTRICT`, so this would surface as a JDBC
 * exception otherwise; we catch and translate for a clearer error.
 */
class CodeListInUseException(
    val codeListId: CodeListId,
    val attributeCount: Long,
) : RuntimeException(
    "Cannot delete code list '${codeListId.key.value}' in catalog '${codeListId.catalogKey.value}': " +
        "it is still referenced by $attributeCount attribute definition(s). Unbind the attributes first.",
)

data class DeleteCodeList(
    val id: CodeListId,
    /** The catalog version this addresses; null for its catalog's current version. */
    override val catalogVersion: CatalogReleaseRef? = null,
) : CatalogReleaseScoped,
    Command<Boolean>,
    RequiresPermission {
    override val permission get() = Permission.REFERENCE_EDIT
    override val tenantKey get() = id.tenantKey
}

@Component
class DeleteCodeListHandler(
    private val jdbi: Jdbi,
    private val codeLists: app.epistola.suite.attributes.codelists.CodeListStore,
) : CommandHandler<DeleteCodeList, Boolean> {
    override fun handle(command: DeleteCodeList): Boolean {
        requireCatalogEditable(command.id.catalogId)
        return jdbi.withHandle<Boolean, Exception> { handle ->
            val attributeCount = handle.createQuery(
                """
                -- Bound by alias and key, resolved through the one alias resolver (ADR 0033).
                SELECT COUNT(*) FROM catalog_version_references('attribute') x
                WHERE x.tenant_key = :tenantKey AND x.ref_type = 'codeList'
                  AND x.ref_catalog_id = ${catalogAt("tenantKey", "catalogKey")} AND x.ref_key = CAST(:slug AS TEXT)
                """,
            )
                .bind("tenantKey", command.id.tenantKey)
                .bindCatalog("catalogKey", command.id.catalogId)
                .bind("slug", command.id.key)
                .mapTo(Long::class.java)
                .one()

            if (attributeCount > 0) {
                throw CodeListInUseException(command.id, attributeCount)
            }

            codeLists.delete(handle, command.id)
        }
    }
}
