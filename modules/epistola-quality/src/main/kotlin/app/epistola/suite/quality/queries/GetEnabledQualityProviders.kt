// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.quality.queries

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.quality.QualitySourceId
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/** The providers a tenant switched on. A provider that is not switchable is on regardless and is not in here. */
data class EnabledQualityProviders(
    val providerIds: Set<QualitySourceId>,
)

/**
 * Which switchable quality providers a tenant switched on (ADR 0047): what a check run reads before
 * asking a provider that sends text somewhere. No row means off.
 *
 * `TEMPLATE_VIEW`, not a settings permission: a check runs for an author, or for the tenant's system
 * user, and both must be able to learn which providers apply. It reveals switches, nothing else.
 */
data class GetEnabledQualityProviders(
    override val tenantKey: TenantKey,
) : Query<EnabledQualityProviders>,
    RequiresPermission {
    override val permission: Permission get() = Permission.TEMPLATE_VIEW
}

@Component
class GetEnabledQualityProvidersHandler(
    private val jdbi: Jdbi,
) : QueryHandler<GetEnabledQualityProviders, EnabledQualityProviders> {
    override fun handle(query: GetEnabledQualityProviders): EnabledQualityProviders = EnabledQualityProviders(
        jdbi.withHandle<List<String>, Exception> { handle ->
            handle.createQuery("SELECT provider_id FROM quality_provider_settings WHERE tenant_key = :tenantKey AND enabled")
                .bind("tenantKey", query.tenantKey)
                .mapTo(String::class.java)
                .list()
        }.map(::QualitySourceId).toSet(),
    )
}
