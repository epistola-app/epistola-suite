// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.commands

import app.epistola.suite.catalog.CatalogReleasePublicationPort
import app.epistola.suite.catalog.revisions.ResourceRevisionStore
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.validation.ValidationException
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.Jdbi
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component

/**
 * Removes a release from a catalog's history, with its content.
 *
 * The stronger of the two ways to reclaim what releases hold — [ForgetReleaseContent] keeps the row
 * and its fingerprint as a record of what the version was; this removes the record too, for a
 * version that should not be part of the history at all.
 *
 * **Already published is not a reason to refuse.** The copy on Epistola Exchange is Exchange's, it
 * stays there, and withdrawing it is a separate conversation with a separate authority. What goes
 * here is this installation's own record of the release, including the outbox rows and archives that
 * hang off it.
 *
 * **What must not be left dangling is the pointer.** `catalogs.released_version`,
 * `released_fingerprint` and `released_at` are a plain pointer at the current release with no foreign
 * key to keep them honest, and `released_at` is what the drift rule in `ListCatalogsForManagement`
 * compares the working copy against. Delete the release they name without moving them, and every
 * catalog screen reports drift against a release that no longer exists. So this re-points them at
 * the new latest release, or clears them when none is left — in the same transaction, because a
 * catalog whose pointer names a deleted release is not a state anything should be able to observe.
 */
data class DeleteCatalogRelease(
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
    val version: String,
) : Command<Boolean>,
    RequiresPermission {
    override val permission get() = Permission.CATALOG_MANAGE
}

@Component
class DeleteCatalogReleaseHandler(
    private val jdbi: Jdbi,
    private val revisions: ResourceRevisionStore,
    /** Absent on an installation with no publication integration, where nothing can be in flight. */
    private val publicationPort: ObjectProvider<CatalogReleasePublicationPort>,
) : CommandHandler<DeleteCatalogRelease, Boolean> {

    /** @return false when there was no such release, so a second click says so rather than throwing. */
    override fun handle(command: DeleteCatalogRelease): Boolean = jdbi.withHandle<Boolean, Exception> { handle ->
        // Refused only while Exchange is mid-send: the outbox row and the archive bytes a sender is
        // working from cascade away with the release. Having *been* published is no reason to refuse.
        requireNoSendInFlight(handle, command)

        val deleted = handle.createUpdate(
            """
            DELETE FROM catalog_releases
            WHERE tenant_key = :t AND catalog_key = :c AND version = :v
            """,
        )
            .bind("t", command.tenantKey)
            .bind("c", command.catalogKey)
            .bind("v", command.version)
            .execute() > 0

        if (!deleted) return@withHandle false

        // Entries and publication rows cascade with the release; the content they named does not.
        revisions.collectUnreferenced(handle, command.tenantKey)
        repointCurrentRelease(handle, command)
        true
    }

    /**
     * Refuses while Exchange is being sent this very release.
     *
     * Not a policy about published releases — those delete freely — but a race: the outbox row and
     * the archive bytes a worker is mid-send with would cascade away underneath it. A publication
     * that has reached a decision, been withdrawn or failed is inert and goes with the release.
     */
    private fun requireNoSendInFlight(handle: Handle, command: DeleteCatalogRelease) {
        val inFlight = publicationPort.ifAvailable?.isSendInFlight(
            handle,
            command.tenantKey,
            command.catalogKey,
            command.version,
        ) ?: false

        if (inFlight) {
            throw ValidationException(
                "version",
                "v${command.version} is being published to Epistola Exchange right now. " +
                    "Withdraw that publication first, then delete the release.",
            )
        }
    }

    /**
     * Moves the catalog's current-release pointer to whatever is now latest, or clears it.
     *
     * Deliberately re-derived from the remaining rows rather than assuming the deleted release was
     * the latest: deleting an older one must leave the pointer alone, and the same statement does
     * both. `LATEST_RELEASE_ORDER` is not reused here because this is an UPDATE ... FROM rather than
     * a SELECT, and one ordering rule spelled twice is better than a constant bent to fit both.
     */
    private fun repointCurrentRelease(handle: Handle, command: DeleteCatalogRelease) {
        handle.createUpdate(
            """
            UPDATE catalogs c
            SET released_version = latest.version,
                released_fingerprint = latest.fingerprint,
                released_at = latest.released_at,
                updated_at = NOW()
            FROM (
                SELECT r.version, r.fingerprint, r.released_at
                FROM catalog_releases r
                WHERE r.tenant_key = :t AND r.catalog_key = :c
                ORDER BY r.version_major DESC NULLS LAST, r.version_minor DESC NULLS LAST,
                         r.version_patch DESC NULLS LAST, r.released_at DESC
                LIMIT 1
            ) AS latest
            WHERE c.tenant_key = :t AND c.id = :c
            """,
        )
            .bind("t", command.tenantKey)
            .bind("c", command.catalogKey)
            .execute()
            .let { rows ->
                // No rows updated means the subquery found nothing: the catalog has no releases
                // left, so the pointer has to be cleared rather than left naming the deleted one.
                if (rows == 0) clearCurrentRelease(handle, command)
            }
    }

    private fun clearCurrentRelease(handle: Handle, command: DeleteCatalogRelease) {
        handle.createUpdate(
            """
            UPDATE catalogs
            SET released_version = NULL, released_fingerprint = NULL, released_at = NULL, updated_at = NOW()
            WHERE tenant_key = :t AND id = :c
            """,
        )
            .bind("t", command.tenantKey)
            .bind("c", command.catalogKey)
            .execute()
    }
}
