// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.mirror

import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.PlatformRole
import app.epistola.suite.security.RequiresPlatformRole
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/**
 * Forgets every tenant's catalog-mirror choice, the installation's preset and where each tenant's
 * reconcile stood, as the installation disconnected from Epistola support, which deleted the mirror:
 * after registering again every tenant is asked again, and what it shares then is sent anew. Each
 * catalog's own policy (share, never share) stays: it is about the catalog, not a consent.
 */
class ForgetCatalogMirrorSharing :
    Command<ForgottenCatalogMirrorSharing>,
    RequiresPlatformRole {
    override val platformRole = PlatformRole.INSTALLATION_ADMINISTRATOR
}

/** What [ForgetCatalogMirrorSharing] did: the tenants whose choice it forgot. */
data class ForgottenCatalogMirrorSharing(val tenants: Int)

@Component
class ForgetCatalogMirrorSharingHandler(
    private val jdbi: Jdbi,
) : CommandHandler<ForgetCatalogMirrorSharing, ForgottenCatalogMirrorSharing> {
    override fun handle(command: ForgetCatalogMirrorSharing): ForgottenCatalogMirrorSharing = jdbi.withHandle<ForgottenCatalogMirrorSharing, Exception> { handle ->
        ForgottenCatalogMirrorSharing(forgetMirrorSharing(handle))
    }
}

/** Deletes every tenant's choice, where its reconcile stood, and the preset; the number of tenants whose choice went. */
internal fun forgetMirrorSharing(handle: org.jdbi.v3.core.Handle): Int {
    val tenants = handle.createUpdate("DELETE FROM support_sharing_choice WHERE ${mirrorSharing()}").execute()
    handle.createUpdate("DELETE FROM support_mirror_state WHERE connection_id = $MIRROR_CONNECTION_SQL").execute()
    handle.createUpdate("DELETE FROM support_sharing_default WHERE ${mirrorSharing()}").execute()
    return tenants
}
