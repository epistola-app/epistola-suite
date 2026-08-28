// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.exchange

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.security.SystemInternal
import app.epistola.suite.time.EpistolaClock
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

data class GetExchangeConnection(override val tenantKey: TenantKey) :
    Query<ExchangeTenantConnection?>,
    RequiresPermission {
    override val permission = Permission.TENANT_SETTINGS
}

/** Everything the Exchange settings page renders, so the UI resolves no policy of its own. */
data class ExchangeSettings(
    val deploymentEnabled: Boolean,
    val featureEnabled: Boolean,
    val connection: ExchangeTenantConnection?,
    val activity: ExchangePublicationActivity,
) {
    /** What the page shows as the connection's state; an absent connection reads as not connected. */
    val status: ExchangeConnectionStatus get() = connection?.status ?: ExchangeConnectionStatus.PENDING

    val connected: Boolean get() = connection?.status == ExchangeConnectionStatus.ACTIVE
    val needsReauthorization: Boolean get() = connection?.status == ExchangeConnectionStatus.REAUTHORIZATION_REQUIRED

    /** A default only has to be chosen when the connection grants more than one namespace. */
    val choosableNamespaces: List<String> get() = connection?.namespaces?.takeIf { it.size > 1 }.orEmpty()
}

data class GetExchangeSettings(override val tenantKey: TenantKey) :
    Query<ExchangeSettings>,
    RequiresPermission {
    override val permission = Permission.TENANT_SETTINGS
}

data class FindExchangeAuthorizationTenant(
    val state: String,
) : Query<TenantKey?>,
    SystemInternal

@Component
class GetExchangeConnectionHandler(
    private val credentials: ExchangeCredentialService,
) : QueryHandler<GetExchangeConnection, ExchangeTenantConnection?> {
    override fun handle(query: GetExchangeConnection): ExchangeTenantConnection? = credentials.connection(query.tenantKey)
}

@Component
class GetExchangeSettingsHandler(
    private val availability: ExchangeAvailability,
    private val credentials: ExchangeCredentialService,
) : QueryHandler<GetExchangeSettings, ExchangeSettings> {
    override fun handle(query: GetExchangeSettings): ExchangeSettings = ExchangeSettings(
        deploymentEnabled = availability.deploymentEnabled,
        featureEnabled = availability.isAvailable(query.tenantKey),
        connection = credentials.connection(query.tenantKey),
        activity = GetExchangePublicationActivity(query.tenantKey).query(),
    )
}

@Component
class FindExchangeAuthorizationTenantHandler(
    private val jdbi: Jdbi,
) : QueryHandler<FindExchangeAuthorizationTenant, TenantKey?> {
    // `expires_at` is written from the application clock, so it must be compared against the
    // application clock too — mixing it with the database's NOW() would compare two different clocks.
    override fun handle(query: FindExchangeAuthorizationTenant): TenantKey? = jdbi.withHandle<TenantKey?, Exception> { handle ->
        handle.createQuery(
            "SELECT tenant_key FROM exchange_oauth_authorizations WHERE state_hash = :stateHash AND expires_at > :now",
        ).bind("stateHash", sha256(query.state)).bind("now", EpistolaClock.offsetDateTime())
            .mapTo<String>().findOne().map(TenantKey::of).orElse(null)
    }
}
