// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.attributes.codelists.commands

import app.epistola.suite.attributes.codelists.CODE_LIST_AT_ADDRESS
import app.epistola.suite.attributes.codelists.CodeListStore
import app.epistola.suite.attributes.codelists.model.CodeList
import app.epistola.suite.attributes.codelists.model.CodeListSource
import app.epistola.suite.attributes.codelists.service.CodeListClient
import app.epistola.suite.attributes.codelists.service.CodeListFetchException
import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.CatalogReleaseScoped
import app.epistola.suite.catalog.releases.CatalogReleaseTargeting
import app.epistola.suite.catalog.requireCatalogEditable
import app.epistola.suite.catalog.revisions.DraftResources
import app.epistola.suite.catalog.versionAt
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
    /** The catalog version this addresses; null for its catalog's current version. */
    override val catalogVersion: CatalogReleaseRef? = null,
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
    private val targeting: CatalogReleaseTargeting,
    private val codeLists: CodeListStore,
) : CommandHandler<RefreshCodeList, CodeList?> {
    private val logger = LoggerFactory.getLogger(javaClass)

    override fun handle(command: RefreshCodeList): CodeList? {
        requireCatalogEditable(command.id.catalogId)

        // Self-managed: each of its transactions takes the catalog version the command targets.
        val current = load(command.id) ?: return null

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
            logger.warn("Refreshing code list {} from {} failed: {}", command.id, sourceUrl, e.message, e)
            recordRefresh(command.id, succeeded = false, error = REFRESH_FAILED_MESSAGE)
            return load(command.id)
        }

        try {
            validateCodeListEntries(fetched)
        } catch (e: ValidationException) {
            recordRefresh(command.id, succeeded = false, error = e.message)
            return load(command.id)
        }

        return jdbi.inTransaction<CodeList?, Exception> { handle ->
            targeting.apply(handle)
            DraftResources.lockRevision(handle, command.id.catalogId, CodeListStore.CODE_LIST, command.id.key.value) ?: return@inTransaction null
            val stored = checkNotNull(codeLists.fields(handle, command.id)) { "Code list ${command.id.key.value} is locked, so it is there" }
            val refreshed = stored.copy(entries = fetched)
            // The same entries are the same content: only the refresh is recorded, so the revision and
            // a ready mark stay as they were (ADR 0033).
            if (codeLists.payload(command.id.key.value, refreshed) != codeLists.payload(command.id.key.value, stored)) {
                codeLists.save(handle, command.id, refreshed, currentUserIdOrNull()?.value)
            }
            codeLists.recordRefresh(handle, command.id, succeeded = true, error = null)
            codeLists.get(handle, command.id)
        }
    }

    private fun recordRefresh(id: CodeListId, succeeded: Boolean, error: String?) {
        jdbi.useTransaction<Exception> { handle ->
            targeting.apply(handle)
            codeLists.recordRefresh(handle, id, succeeded, error)
        }
    }

    private fun load(id: CodeListId): CodeList? = jdbi.inTransaction<CodeList?, Exception> { handle ->
        targeting.apply(handle)
        codeLists.get(handle, id)
    }
}
