// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.attributes.codelists.commands

import app.epistola.suite.attributes.codelists.CODE_LIST_AT_ADDRESS
import app.epistola.suite.attributes.codelists.CodeListFields
import app.epistola.suite.attributes.codelists.CodeListStore
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
import app.epistola.suite.catalog.revisions.DraftResources
import app.epistola.suite.catalog.versionAt
import app.epistola.suite.common.Revisions
import app.epistola.suite.common.ids.CodeListId
import app.epistola.suite.crypto.Secret
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.security.currentUserIdOrNull
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
    private val codeLists: CodeListStore,
) : CommandHandler<UpdateCodeList, CodeList?> {
    override fun handle(command: UpdateCodeList): CodeList? {
        requireCatalogEditable(command.id.catalogId)
        return jdbi.withHandle<CodeList?, Exception> { handle ->
            // Locked, so the change is applied to what is there, and two writers do not race.
            val revision = DraftResources.lockRevision(handle, command.id.catalogId, CodeListStore.CODE_LIST, command.id.key.value) ?: return@withHandle null
            Revisions.refuse("Code list '${command.id.key.value}'", command.expectedRevision, revision)
            val current = checkNotNull(codeLists.fields(handle, command.id)) { "Code list ${command.id.key.value} is locked, so it is there" }
            command.sourceUrl?.let { newUrl ->
                // Only a changed URL is checked: a PATCH that replays the stored one (the REST API does)
                // must not fail because of an unrelated field. The stored URL is checked again on refresh.
                if (newUrl != current.source?.url) codeListClient.requireAllowedSourceUrl(newUrl)
            }
            val source = current.source?.let {
                it.copy(
                    url = command.sourceUrl ?: it.url,
                    auth = command.authType,
                    // A credential given is a new one; none given clears it, as the column did.
                    credentialId = command.credential?.let { secret -> codeLists.storeCredential(handle, command.id.catalogId, secret) },
                )
            }
            // Entries are replaced only for an INLINE list; a sourced list's come from RefreshCodeList.
            val entries = if (command.entries != null && current.source == null) command.entries else current.entries
            codeLists.save(handle, command.id, CodeListFields(command.displayName, command.description, entries, source), currentUserIdOrNull()?.value)
        }
    }
}
