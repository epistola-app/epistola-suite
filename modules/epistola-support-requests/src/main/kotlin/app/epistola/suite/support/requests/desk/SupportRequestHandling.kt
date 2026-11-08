// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.requests.desk

import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.metadata.AppMetadataService
import app.epistola.suite.metadata.getAs
import app.epistola.suite.security.PlatformRole
import app.epistola.suite.security.RequiresPlatformRole
import app.epistola.suite.security.SecurityContext
import app.epistola.suite.support.requests.consent.REQUESTS_CONNECTION
import app.epistola.suite.support.requests.sync.SupportRequestSyncPort
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.withHandleUnchecked
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import java.util.UUID

/** Who handles new support requests while no installation administrator has saved a choice. */
enum class SupportRequestHandledBy {
    /** The installation's own inbox; the desk passes requests on. */
    INBOX,

    /** Epistola support: new requests go there, as with the pilot. */
    EPISTOLA,
}

/**
 * `epistola.support.requests.handled-by` (Helm `support.requests.handledBy`): who handles new
 * support requests until an installation administrator saves a choice on the support desk, which then
 * wins. Default [SupportRequestHandledBy.INBOX].
 */
@ConfigurationProperties(prefix = "epistola.support.requests")
data class SupportRequestHandlingProperties(
    val handledBy: SupportRequestHandledBy = SupportRequestHandledBy.INBOX,
)

/**
 * Where new support requests go: the choice an installation administrator saved
 * (`support_connections.handles_new_requests`, marked saved in `app_metadata` under [SAVED_KEY]), or
 * else the configuration ([SupportRequestHandlingProperties]). Read per request, so a node follows a
 * saved choice at once.
 */
@Component
class SupportRequestHandlingPolicy(
    private val jdbi: Jdbi,
    private val appMetadata: AppMetadataService,
    private val properties: SupportRequestHandlingProperties,
) {
    /** An installation administrator saved a choice, which wins over the configuration. */
    fun saved(): Boolean = appMetadata.getAs<Saved>(SAVED_KEY)?.saved == true

    /** The connection new requests go to; null: the inbox handles them. */
    fun connectionForNewRequests(): UUID? = if (saved()) {
        jdbi.withHandleUnchecked { handle ->
            handle.createQuery("SELECT id FROM support_connections WHERE handles_new_requests").mapTo(UUID::class.java).findOne().orElse(null)
        }
    } else {
        when (properties.handledBy) {
            SupportRequestHandledBy.INBOX -> null
            SupportRequestHandledBy.EPISTOLA -> REQUESTS_CONNECTION
        }
    }

    internal fun markSaved(saved: Boolean) = appMetadata.setAs(SAVED_KEY, Saved(saved))

    /** Whether a choice was saved, as stored in `app_metadata`. */
    data class Saved(val saved: Boolean)

    companion object {
        const val SAVED_KEY = "support.requests.handling-saved"
    }
}

/**
 * Who may work the support desk: the installation's inbox of support requests across tenants
 * (ADR 0053 §3–4). Who handles new requests stays the installation administrator's to set.
 */
internal val SUPPORT_DESK_ROLE: PlatformRole = PlatformRole.SUPPORT_DESK

/** A support connection new support requests can be handed to. */
data class SupportRequestHandlerChoice(val connectionId: UUID, val name: String)

/**
 * Who handles new support requests (ADR 0053 §4): the installation's own inbox ([connectionId] null)
 * or a support connection. Requests already handed to a connection stay with it when this changes.
 *
 * @property choices the connections new requests can go to now; empty while none is connected.
 */
data class SupportRequestHandling(
    val connectionId: UUID?,
    val connectionName: String?,
    val choices: List<SupportRequestHandlerChoice>,
    /** No installation administrator saved a choice: the configuration (`epistola.support.requests.handled-by`) applies. */
    val fromConfiguration: Boolean = false,
    /**
     * The reader may change who handles new requests: an installation administrator. The desk
     * page shows everyone else the choice without the form.
     */
    val mayChange: Boolean = false,
) {
    /** New requests stay in the installation's inbox. */
    val handledHere: Boolean get() = connectionId == null
}

/** Who handles new support requests, and who could. */
class GetSupportRequestHandling :
    Query<SupportRequestHandling>,
    RequiresPlatformRole {
    override val platformRole = SUPPORT_DESK_ROLE
}

@Component
class GetSupportRequestHandlingHandler(
    private val jdbi: Jdbi,
    private val port: SupportRequestSyncPort,
    private val policy: SupportRequestHandlingPolicy,
) : QueryHandler<GetSupportRequestHandling, SupportRequestHandling> {
    override fun handle(query: GetSupportRequestHandling): SupportRequestHandling = jdbi.withHandleUnchecked { readHandling(it, port, policy) }
        .copy(mayChange = SecurityContext.current().hasPlatformRole(PlatformRole.INSTALLATION_ADMINISTRATOR))
}

/**
 * New support requests go to [connectionId], or stay in the installation's inbox (null). Requests
 * already handed to a connection stay with it. Only a connection among the choices may be named.
 */
data class SetSupportRequestHandling(
    val connectionId: UUID?,
) : Command<SupportRequestHandling>,
    RequiresPlatformRole {
    override val platformRole = PlatformRole.INSTALLATION_ADMINISTRATOR
}

@Component
class SetSupportRequestHandlingHandler(
    private val jdbi: Jdbi,
    private val port: SupportRequestSyncPort,
    private val policy: SupportRequestHandlingPolicy,
) : CommandHandler<SetSupportRequestHandling, SupportRequestHandling> {
    override fun handle(command: SetSupportRequestHandling): SupportRequestHandling = jdbi.inTransaction<SupportRequestHandling, Exception> { handle ->
        if (command.connectionId != null && choices(handle, port).none { it.connectionId == command.connectionId }) {
            throw IllegalArgumentException("Support requests cannot go to connection ${command.connectionId}")
        }
        // Two statements: at most one row may have the flag, checked row by row.
        handle.createUpdate("UPDATE support_connections SET handles_new_requests = FALSE WHERE handles_new_requests").execute()
        command.connectionId?.let { id ->
            handle.createUpdate("UPDATE support_connections SET handles_new_requests = TRUE WHERE id = :id").bind("id", id).execute()
        }
        policy.markSaved(true)
        readHandling(handle, port, policy)
    }
}

/** Forgets the saved choice: new support requests follow the configuration again. Requests already handed on stay where they are. */
class ResetSupportRequestHandling :
    Command<SupportRequestHandling>,
    RequiresPlatformRole {
    override val platformRole = PlatformRole.INSTALLATION_ADMINISTRATOR
}

@Component
class ResetSupportRequestHandlingHandler(
    private val jdbi: Jdbi,
    private val port: SupportRequestSyncPort,
    private val policy: SupportRequestHandlingPolicy,
) : CommandHandler<ResetSupportRequestHandling, SupportRequestHandling> {
    override fun handle(command: ResetSupportRequestHandling): SupportRequestHandling = jdbi.inTransaction<SupportRequestHandling, Exception> { handle ->
        handle.createUpdate("UPDATE support_connections SET handles_new_requests = FALSE WHERE handles_new_requests").execute()
        policy.markSaved(false)
        readHandling(handle, port, policy)
    }
}

/**
 * The connections support requests can be handed to: the one requests are sent over
 * ([REQUESTS_CONNECTION]) while a sync target is wired. More come with partner connections.
 */
internal fun choices(handle: Handle, port: SupportRequestSyncPort): List<SupportRequestHandlerChoice> {
    if (!port.isEnabled()) return emptyList()
    return handle.createQuery("SELECT id, name FROM support_connections WHERE id = :id")
        .bind("id", REQUESTS_CONNECTION)
        .map { rs, _ -> SupportRequestHandlerChoice(rs.getObject("id", UUID::class.java), rs.getString("name")) }
        .list()
}

private fun readHandling(handle: Handle, port: SupportRequestSyncPort, policy: SupportRequestHandlingPolicy): SupportRequestHandling {
    val id = policy.connectionForNewRequests()
    val name = id?.let { handle.createQuery("SELECT name FROM support_connections WHERE id = :id").bind("id", it).mapTo(String::class.java).findOne().orElse(null) }
    return SupportRequestHandling(id, name, choices(handle, port), fromConfiguration = !policy.saved())
}
