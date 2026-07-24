// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.attributes.codelists.commands

import app.epistola.suite.attributes.codelists.model.CodeList
import app.epistola.suite.attributes.codelists.model.CodeListEntry
import app.epistola.suite.attributes.codelists.model.CodeListSource
import app.epistola.suite.catalog.AuthType
import app.epistola.suite.catalog.requireCatalogEditable
import app.epistola.suite.common.ids.CodeListId
import app.epistola.suite.crypto.Secret
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.validation.FieldLimits.MAX_NAME_LENGTH
import app.epistola.suite.validation.validate
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

/**
 * Updates code list metadata and (for INLINE lists only) replaces entries.
 *
 * For URL-sourced lists, entries are managed via `RefreshCodeList` rather than
 * direct edits — `entries` here is ignored when the existing list's
 * `sourceType` is `URL` or `CLASSPATH`. Per-entry visibility toggling lives in
 * `UpdateCodeListEntryHidden`.
 *
 * The `slug` of a code list is immutable. The `sourceType` cannot be changed
 * post-create either (changing it would invalidate the entry maintenance
 * workflow); a code list whose source has fundamentally changed should be
 * recreated.
 */
data class UpdateCodeList(
    val id: CodeListId,
    val displayName: String,
    val description: String? = null,
    val sourceUrl: String? = null,
    val authType: AuthType = AuthType.NONE,
    val credential: String? = null,
    val entries: List<CodeListEntry>? = null,
) : Command<CodeList?>,
    RequiresPermission {
    override val permission get() = Permission.REFERENCE_EDIT
    override val tenantKey get() = id.tenantKey

    init {
        validate("displayName", displayName.isNotBlank()) { "Display name is required" }
        validate("displayName", displayName.length <= MAX_NAME_LENGTH) { "Display name must be $MAX_NAME_LENGTH characters or less" }
        if (entries != null) {
            validateCodeListEntries(entries)
        }
    }
}

@Component
class UpdateCodeListHandler(
    private val jdbi: Jdbi,
) : CommandHandler<UpdateCodeList, CodeList?> {
    override fun handle(command: UpdateCodeList): CodeList? {
        requireCatalogEditable(command.id.tenantKey, command.id.catalogKey)
        return jdbi.inTransaction<CodeList?, Exception> { handle ->
            val updated = handle.createQuery(
                """
                UPDATE code_lists
                SET display_name  = :displayName,
                    description   = :description,
                    source_url    = COALESCE(:sourceUrl, source_url),
                    auth_type     = :authType,
                    credential    = :credential,
                    updated_at = NOW()
                WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey AND slug = :slug
                RETURNING slug, tenant_key, catalog_key, display_name, description,
                          source_type, source_url, auth_type, credential,
                          last_refreshed_at, last_refresh_error,
                          created_at, updated_at
                """,
            )
                .bind("slug", command.id.key)
                .bind("tenantKey", command.id.tenantKey)
                .bind("catalogKey", command.id.catalogKey)
                .bind("displayName", command.displayName)
                .bind("description", command.description)
                .bind("sourceUrl", command.sourceUrl)
                .bind("authType", command.authType.name)
                .bind("credential", command.credential?.let(::Secret))
                .mapTo<CodeList>()
                .findOne()
                .orElse(null) ?: return@inTransaction null

            // Entry replacement is only allowed for INLINE lists; URL/CLASSPATH
            // entries flow through RefreshCodeList. Silently ignore entries on
            // non-INLINE updates (the UI should not present an entry editor in
            // those cases anyway).
            if (command.entries != null && updated.sourceType == CodeListSource.INLINE) {
                handle.createUpdate(
                    """
                    DELETE FROM code_list_entries
                    WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey AND code_list_slug = :slug
                    """,
                )
                    .bind("tenantKey", command.id.tenantKey)
                    .bind("catalogKey", command.id.catalogKey)
                    .bind("slug", command.id.key)
                    .execute()
                insertEntries(handle, command.id, command.entries)
            }
            updated
        }
    }
}
