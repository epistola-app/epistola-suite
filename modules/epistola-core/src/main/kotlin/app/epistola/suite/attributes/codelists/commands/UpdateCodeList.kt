// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.attributes.codelists.commands

import app.epistola.suite.attributes.codelists.CODE_LIST_AT_ADDRESS
import app.epistola.suite.attributes.codelists.model.CodeList
import app.epistola.suite.attributes.codelists.model.CodeListEntry
import app.epistola.suite.attributes.codelists.model.CodeListSource
import app.epistola.suite.attributes.codelists.service.CodeListClient
import app.epistola.suite.catalog.AuthType
import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.contentRevision
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.CatalogReleaseScoped
import app.epistola.suite.catalog.requireCatalogEditable
import app.epistola.suite.catalog.versionAt
import app.epistola.suite.common.Revisions
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
    /** The catalog version this addresses; null for its catalog's current version. */
    override val catalogVersion: CatalogReleaseRef? = null,
    /** The code list's revision the change was made against; null to write whatever it is at now. */
    val expectedRevision: Long? = null,
) : CatalogReleaseScoped,
    Command<CodeList?>,
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
    private val codeListClient: CodeListClient,
) : CommandHandler<UpdateCodeList, CodeList?> {
    override fun handle(command: UpdateCodeList): CodeList? {
        requireCatalogEditable(command.id.catalogId)
        command.sourceUrl?.let { newUrl ->
            // Only a changed URL is checked: a PATCH that replays the stored one (the REST API does)
            // must not fail because of an unrelated field. The stored URL is checked again on refresh.
            if (newUrl != currentSourceUrl(command)) codeListClient.requireAllowedSourceUrl(newUrl)
        }
        return jdbi.inTransaction<CodeList?, Exception> { handle ->
            val updated = handle.createQuery(
                """
                UPDATE code_lists
                SET display_name  = :displayName,
                    description   = :description,
                    source_url    = COALESCE(:sourceUrl, source_url),
                    auth_type     = :authType,
                    credential    = :credential,
                    updated_at = NOW(),
                    -- Replacing an inline list's entries changes the list, which its own columns do not show.
                    revision      = CASE WHEN :replacesEntries AND source_type = 'INLINE' THEN revision + 1 ELSE revision END
                WHERE tenant_key = :tenantKey AND version_id = ${versionAt("tenantKey", "catalogKey")} AND slug = :slug
                  AND ${Revisions.MATCHES}
                RETURNING slug, tenant_key, (SELECT cat.catalog_key FROM catalogs cat
                            WHERE cat.tenant_key = code_lists.tenant_key AND cat.catalog_id = code_lists.catalog_id) AS catalog_key, (SELECT cat.namespace FROM catalogs cat
                            WHERE cat.tenant_key = code_lists.tenant_key AND cat.catalog_id = code_lists.catalog_id) AS namespace,
                          display_name, description,
                          source_type, source_url, auth_type, credential,
                          last_refreshed_at, last_refresh_error,
                          created_at, updated_at, revision
                """,
            )
                .bind("slug", command.id.key)
                .bind("tenantKey", command.id.tenantKey)
                .bindCatalog("catalogKey", command.id.catalogId)
                .bind("displayName", command.displayName)
                .bind("description", command.description)
                .bind("sourceUrl", command.sourceUrl)
                .bind("authType", command.authType.name)
                .bind("credential", command.credential?.let(::Secret))
                .bind("replacesEntries", command.entries != null)
                .bind("expectedRevision", command.expectedRevision)
                .mapTo<CodeList>()
                .findOne()
                .orElse(null)
                ?: run {
                    Revisions.refuse("Code list '${command.id.key.value}'", command.expectedRevision, handle.contentRevision("code_lists", "slug", command.id.catalogId, command.id.key))
                    return@inTransaction null
                }

            // Entry replacement is only allowed for INLINE lists; URL/CLASSPATH
            // entries flow through RefreshCodeList. Silently ignore entries on
            // non-INLINE updates (the UI should not present an entry editor in
            // those cases anyway).
            if (command.entries != null && updated.sourceType == CodeListSource.INLINE) {
                handle.createUpdate(
                    """
                    DELETE FROM code_list_entries
                    WHERE tenant_key = :tenantKey AND version_id = ${versionAt("tenantKey", "catalogKey")} AND code_list_resource_id = $CODE_LIST_AT_ADDRESS
                    """,
                )
                    .bind("tenantKey", command.id.tenantKey)
                    .bindCatalog("catalogKey", command.id.catalogId)
                    .bind("slug", command.id.key)
                    .execute()
                insertEntries(handle, command.id, command.entries)
            }
            updated
        }
    }

    private fun currentSourceUrl(command: UpdateCodeList): String? = jdbi.withHandle<String?, Exception> { handle ->
        handle.createQuery(
            """
            SELECT source_url FROM code_lists
            WHERE tenant_key = :tenantKey AND version_id = ${versionAt("tenantKey", "catalogKey")} AND slug = :slug
            """,
        )
            .bind("tenantKey", command.id.tenantKey)
            .bindCatalog("catalogKey", command.id.catalogId)
            .bind("slug", command.id.key)
            .mapTo(String::class.java)
            .findOne()
            .orElse(null)
    }
}
