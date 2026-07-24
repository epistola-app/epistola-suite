// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.commands

import app.epistola.suite.attributes.codelists.commands.insertEntries
import app.epistola.suite.attributes.codelists.commands.validateCodeListEntries
import app.epistola.suite.attributes.codelists.model.CodeListEntry
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.CodeListId
import app.epistola.suite.common.ids.CodeListKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.validation.FieldLimits.MAX_DISPLAY_NAME_COLUMN_LENGTH
import app.epistola.suite.validation.validate
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/**
 * Catalog-import counterpart to `CreateCodeList` — UPSERTs a code list and
 * its entries from a `CodeListResource` (wire format). Matches the
 * `ImportAttribute` / `ImportTheme` / `ImportStencil` shape used by
 * `InstallFromCatalog`.
 *
 * Imported code lists are persisted with `source_type = INLINE` and a NULL
 * `source_url`: the catalog itself is the source of record, and the
 * standalone code-list refresh path doesn't apply to entries that were
 * baked into a catalog manifest. Re-running this command (e.g. a catalog
 * upgrade) replaces the entry set atomically.
 */
data class ImportCodeList(
    val tenantId: TenantId,
    val catalogKey: CatalogKey,
    val slug: String,
    val displayName: String,
    val description: String? = null,
    val entries: List<CodeListEntry> = emptyList(),
) : Command<InstallStatus>,
    RequiresPermission {
    override val permission get() = Permission.REFERENCE_EDIT
    override val tenantKey: TenantKey get() = tenantId.key

    init {
        // Column ceiling (#692): code_lists.display_name is VARCHAR(100).
        validate("displayName", displayName.length <= MAX_DISPLAY_NAME_COLUMN_LENGTH) { "Display name must be $MAX_DISPLAY_NAME_COLUMN_LENGTH characters or less" }
        validateCodeListEntries(entries)
    }
}

@Component
class ImportCodeListHandler(
    private val jdbi: Jdbi,
) : CommandHandler<ImportCodeList, InstallStatus> {

    override fun handle(command: ImportCodeList): InstallStatus {
        val codeListSlug = CodeListKey.of(command.slug)

        return jdbi.inTransaction<InstallStatus, Exception> { handle ->
            // `xmax = 0` is true only for the freshly INSERTed row and false when
            // the ON CONFLICT branch UPDATEd an existing one — so the upsert reports
            // INSTALLED vs UPDATED itself, without a preceding existence SELECT.
            val inserted = handle.createQuery(
                """
                INSERT INTO code_lists (slug, tenant_key, catalog_key, display_name, description, source_type, source_url, auth_type, created_at, updated_at)
                VALUES (:slug, :tenantKey, :catalogKey, :displayName, :description, 'INLINE', NULL, 'NONE', NOW(), NOW())
                ON CONFLICT (tenant_key, catalog_key, slug) DO UPDATE
                SET display_name  = :displayName,
                    description   = :description,
                    updated_at = NOW()
                RETURNING (xmax = 0) AS inserted
                """,
            )
                .bind("slug", codeListSlug)
                .bind("tenantKey", command.tenantKey)
                .bind("catalogKey", command.catalogKey)
                .bind("displayName", command.displayName)
                .bind("description", command.description)
                .mapTo(Boolean::class.java)
                .one()

            // Replace entries atomically. Hidden flags and sort order land
            // straight from the catalog file — the importing tenant doesn't
            // have its own copy to preserve.
            handle.createUpdate(
                """
                DELETE FROM code_list_entries
                WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey AND code_list_slug = :slug
                """,
            )
                .bind("tenantKey", command.tenantKey)
                .bind("catalogKey", command.catalogKey)
                .bind("slug", codeListSlug)
                .execute()

            insertEntries(handle, CodeListId(codeListSlug, CatalogId(command.catalogKey, command.tenantId)), command.entries)

            if (inserted) InstallStatus.INSTALLED else InstallStatus.UPDATED
        }
    }
}
