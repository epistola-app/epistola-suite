// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.commands

import app.epistola.suite.catalog.CatalogReleasePublicationPort
import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.revisions.ReleaseInUse
import app.epistola.suite.common.ids.CatalogId
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
 * Removes a release from a catalog's history: the way to reclaim what a release holds, such as an
 * image released by mistake. The release and its rows go at once; the content only it held goes when
 * the content collector next runs past the grace period (ADR 0033), with its binaries and references.
 *
 * **Already published is not a reason to refuse.** The copy on Epistola Exchange is Exchange's, it
 * stays there, and withdrawing it is a separate conversation with a separate authority. What goes
 * here is this installation's own record of the release, including the outbox rows and archives that
 * hang off it.
 *
 * **A gate this will need once environments deploy releases.** Today deleting a release costs only
 * the record of it. Once an environment serves a named release (ADR 0026 §6, #1036), deleting the
 * release an environment is on takes that environment down — so this will need to refuse for a
 * deployed release, the same shape as the in-flight-publication refusal below. There is nothing to
 * ask yet: no deployment exists to consult.
 *
 * **No pointer is left dangling.** The catalog's current release is a query over its released rows
 * (ADR 0036, `catalog_current_releases`), so once the release's row is gone the catalog's current
 * release is the highest that remains, or none, with nothing to repoint.
 */
data class DeleteCatalogRelease(
    val catalog: CatalogId,
    val version: String,
) : Command<Boolean>,
    RequiresPermission {
    override val tenantKey: TenantKey get() = catalog.tenantKey
    val catalogKey: CatalogKey get() = catalog.key
    override val permission get() = Permission.CATALOG_MANAGE
}

@Component
class DeleteCatalogReleaseHandler(
    private val jdbi: Jdbi,
    /** Absent on an installation with no publication integration, where nothing can be in flight. */
    private val publicationPort: ObjectProvider<CatalogReleasePublicationPort>,
) : CommandHandler<DeleteCatalogRelease, Boolean> {

    /** @return false when there was no such release, so a second click says so rather than throwing. */
    override fun handle(command: DeleteCatalogRelease): Boolean = jdbi.withHandle<Boolean, Exception> { handle ->
        // Refused only while Exchange is mid-send: the outbox row and the archive bytes a sender is
        // working from cascade away with the release. Having *been* published is no reason to refuse.
        requireNoSendInFlight(handle, command)
        ReleaseInUse.requireUnused(handle, command.catalog, command.version)

        // Its rows go while its row still says it is a release (ADR 0036), so they are not mistaken for
        // a draft's: a draft's delete touches the catalog's content and its identities, a release's not.
        app.epistola.suite.catalog.revisions.ReleaseResourceStore.deleteRelease(handle, command.catalog, command.version)

        val deleted = handle.createUpdate(
            """
            DELETE FROM catalog_versions
            WHERE state = 'released' AND tenant_key = :t AND catalog_id = ${catalogAt("t", "c")} AND version = :v
            """,
        )
            .bind("t", command.tenantKey)
            .bindCatalog("c", command.catalog)
            .bind("v", command.version)
            .execute() > 0

        if (!deleted) return@withHandle false

        // Its rows and publication rows cascade with the release. The content they named is the
        // collector's (ResourceContentCollector, ADR 0033): past the grace period, what no other
        // version holds goes with its binaries and references.
        touchCatalog(handle, command)
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
            command.catalog,
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
     * Records that the catalog changed. Its current release needs no repointing: it is a query over
     * the released rows that remain (ADR 0036, `catalog_current_releases`).
     */
    private fun touchCatalog(handle: Handle, command: DeleteCatalogRelease) {
        handle.createUpdate("UPDATE catalogs SET updated_at = NOW() WHERE tenant_key = :t AND catalog_id = epistola_catalog_id(:t, :c_ns, :c)")
            .bind("t", command.tenantKey)
            .bindCatalog("c", command.catalog)
            .execute()
    }
}
