// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.attributes.codelists.commands

import app.epistola.suite.attributes.codelists.CodeListStore
import app.epistola.suite.attributes.codelists.model.CodeList
import app.epistola.suite.attributes.codelists.model.CodeListSource
import app.epistola.suite.attributes.codelists.service.CodeListClient
import app.epistola.suite.attributes.codelists.service.CodeListFetchException
import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.CatalogReleaseScoped
import app.epistola.suite.catalog.releases.versionOf
import app.epistola.suite.catalog.requireCatalogEditable
import app.epistola.suite.catalog.revisions.DraftResources
import app.epistola.suite.common.ids.CodeListId
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.SelfManagedTransaction
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.security.currentUserIdOrNull
import app.epistola.suite.validation.ValidationException
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Re-fetches entries for a code list from its `source_url` and atomically
 * replaces the existing entries.
 *
 * Only valid for `URL` and `CLASSPATH` source types. INLINE lists are managed
 * via `UpdateCodeList`. On a fetch error the existing entries are kept and
 * `last_refresh_error` is updated; this means the picker keeps working even
 * when the source is temporarily unreachable.
 */
data class RefreshCodeList(
    val id: CodeListId,
    /** The catalog version this addresses; null for its catalog's next version. */
    override val catalogVersion: CatalogReleaseRef?,
) : CatalogReleaseScoped,
    Command<CodeList?>,
    RequiresPermission,
    // Fetches entries from the source URL mid-command, and a fetch error must be
    // recorded (committed) even though the refresh itself fails.
    SelfManagedTransaction {
    override val permission get() = Permission.REFERENCE_EDIT
    override val tenantKey get() = id.tenantKey
}

class CodeListNotRefreshableException(message: String) : RuntimeException(message)

/** What `last_refresh_error` says when the source could not be read; the cause is logged, not stored. */
const val REFRESH_FAILED_MESSAGE = "The code list could not be fetched from its source. Check the URL and credentials; the server log has the details."

@Component
class RefreshCodeListHandler(
    private val jdbi: Jdbi,
    private val codeListClient: CodeListClient,
    private val codeLists: CodeListStore,
) : CommandHandler<RefreshCodeList, CodeList?> {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun handle(command: RefreshCodeList): CodeList? {
        requireCatalogEditable(command.id.catalogId)

        // Self-managed: each of its transactions resolves the catalog version the command addresses.
        val current = load(command) ?: return null

        if (current.sourceType == CodeListSource.INLINE) {
            throw CodeListNotRefreshableException(
                "Code list '${command.id.key.value}' is INLINE — entries are edited directly, not refreshed from a source.",
            )
        }
        val sourceUrl = current.sourceUrl
            ?: throw CodeListNotRefreshableException("Code list '${command.id.key.value}' has no source URL")

        // Fetch outside the transaction — long-running HTTP shouldn't hold a DB
        // connection. On failure we still record the error so the UI can surface it.
        val fetched = try {
            codeListClient.fetchEntries(sourceUrl, current.authType, current.credential?.value)
        } catch (e: CodeListFetchException) {
            // The detail stays in the server log. What is stored is shown to everyone who can see the
            // code list, and a fetch error can carry whatever the source answered, or reveal what a
            // URL reached — so the stored text says only that the fetch failed.
            log.warn("Refreshing code list failed codeList={} url={} reason=\"{}\"", command.id, sourceUrl, e.message, e)
            recordRefresh(command, succeeded = false, error = REFRESH_FAILED_MESSAGE)
            return load(command)
        }

        try {
            validateCodeListEntries(fetched)
        } catch (e: ValidationException) {
            recordRefresh(command, succeeded = false, error = e.message)
            return load(command)
        }

        return jdbi.inTransaction<CodeList?, Exception> { handle ->
            val at = handle.versionOf(command.id.catalogId, command)
            DraftResources.lockRevision(handle, at, CodeListStore.CODE_LIST, command.id.key.value) ?: return@inTransaction null
            val stored = checkNotNull(codeLists.fields(handle, at, command.id)) { "Code list ${command.id.key.value} is locked, so it is there" }
            val refreshed = stored.copy(entries = fetched)
            // The same entries are the same content: only the refresh is recorded, so the revision and
            // a ready mark stay as they were (ADR 0033).
            if (codeLists.payload(command.id.key.value, refreshed) != codeLists.payload(command.id.key.value, stored)) {
                codeLists.save(handle, at, command.id, refreshed, currentUserIdOrNull()?.value)
            }
            codeLists.recordRefresh(handle, at, command.id, succeeded = true, error = null)
            codeLists.get(handle, at, command.id)
        }
    }

    private fun recordRefresh(command: RefreshCodeList, succeeded: Boolean, error: String?) {
        jdbi.useTransaction<Exception> { handle ->
            codeLists.recordRefresh(handle, handle.versionOf(command.id.catalogId, command), command.id, succeeded, error)
        }
    }

    private fun load(command: RefreshCodeList): CodeList? = jdbi.inTransaction<CodeList?, Exception> { handle ->
        codeLists.get(handle, handle.versionOf(command.id.catalogId, command), command.id)
    }
}
