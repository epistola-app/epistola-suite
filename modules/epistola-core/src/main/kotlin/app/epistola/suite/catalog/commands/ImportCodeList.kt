// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.commands

import app.epistola.suite.attributes.codelists.commands.validateCodeListEntries
import app.epistola.suite.attributes.codelists.model.CodeListEntry
import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.versionAt
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
    val catalog: CatalogId,
    val slug: String,
    val displayName: String,
    val description: String? = null,
    val entries: List<CodeListEntry> = emptyList(),
    /** Producer metadata (contract `CatalogResource.metadata`), as received; null keeps what the draft holds. */
    val metadata: app.epistola.suite.catalog.revisions.ProducerMetadata? = null,
) : Command<InstallStatus>,
    RequiresPermission {
    val tenantId: TenantId get() = catalog.tenantId
    val catalogKey: CatalogKey get() = catalog.key
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
    private val codeLists: app.epistola.suite.attributes.codelists.CodeListStore,
) : CommandHandler<ImportCodeList, InstallStatus> {

    override fun handle(command: ImportCodeList): InstallStatus {
        val codeListSlug = CodeListKey.of(command.slug)
        // Hidden flags and sort order land straight from the catalog file: the importing tenant has no
        // copy of its own to preserve. A list already there keeps its source (ADR 0033).
        val existed = jdbi.withHandle<Boolean, Exception> { handle ->
            codeLists.put(handle, CodeListId(codeListSlug, command.catalog), command.displayName, command.description, command.entries, app.epistola.suite.security.currentUserIdOrNull()?.value, command.metadata)
        }
        return if (existed) InstallStatus.UPDATED else InstallStatus.INSTALLED
    }
}
