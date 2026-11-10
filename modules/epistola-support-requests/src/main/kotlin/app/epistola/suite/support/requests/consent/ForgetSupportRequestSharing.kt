// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.requests.consent

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.PlatformRole
import app.epistola.suite.security.RequiresPlatformRole
import app.epistola.suite.support.connection.SupportConnections
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.withHandleUnchecked
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Forgets every tenant's support-request-sharing choice and the installation's preset, as the installation
 * disconnected from Epistola support: what waits to be sent stays here, and after registering again
 * every tenant is asked again. The feedback itself stays.
 */
data class ForgetSupportRequestSharing(
    val connectionId: UUID,
) : Command<ForgottenSupportRequestSharing>,
    RequiresPlatformRole {
    override val platformRole = PlatformRole.INSTALLATION_ADMINISTRATOR
}

/** What [ForgetSupportRequestSharing] did: the tenants whose choice it forgot, and the feedback items that were waiting. */
data class ForgottenSupportRequestSharing(val tenants: Int, val stoppedSupportRequest: Int)

@Component
class ForgetSupportRequestSharingHandler(
    private val jdbi: Jdbi,
) : CommandHandler<ForgetSupportRequestSharing, ForgottenSupportRequestSharing> {
    override fun handle(command: ForgetSupportRequestSharing): ForgottenSupportRequestSharing = jdbi.withHandleUnchecked { handle ->
        val tenants = handle.createQuery("SELECT tenant_key FROM support_sharing_choice WHERE connection_id = :connectionId AND section = '$REQUESTS_SECTION' ORDER BY tenant_key FOR UPDATE")
            .bind("connectionId", command.connectionId)
            .mapTo(String::class.java).list().map { TenantKey.of(it) }
        val stopped = SupportRequestConsentWrites.stopWaiting(handle, tenants, command.connectionId)
        handle.createUpdate("DELETE FROM support_sharing_choice WHERE connection_id = :connectionId AND section = '$REQUESTS_SECTION'").bind("connectionId", command.connectionId).execute()
        handle.createUpdate("DELETE FROM support_sharing_default WHERE connection_id = :connectionId AND section = '$REQUESTS_SECTION'").bind("connectionId", command.connectionId).execute()
        ForgottenSupportRequestSharing(tenants.size, stopped)
    }
}
