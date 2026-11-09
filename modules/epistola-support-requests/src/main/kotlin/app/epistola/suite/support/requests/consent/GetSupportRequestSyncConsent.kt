// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.requests.consent

import app.epistola.suite.common.ids.SupportRequestId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.security.SystemInternal
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.withHandleUnchecked
import org.springframework.stereotype.Component

/** The tenant's consent to send feedback to Epistola support, for its settings page. */
data class GetSupportRequestSyncConsent(
    override val tenantKey: TenantKey,
) : Query<SupportRequestSyncConsent>,
    RequiresPermission {
    override val permission get() = Permission.INTEGRATIONS_MANAGE
}

@Component
class GetSupportRequestSyncConsentHandler(
    private val jdbi: Jdbi,
) : QueryHandler<GetSupportRequestSyncConsent, SupportRequestSyncConsent> {
    override fun handle(query: GetSupportRequestSyncConsent): SupportRequestSyncConsent = readConsent(jdbi, query.tenantKey)
}

/**
 * Whether the tenant's consent is in force, for the sync drivers. They run for whoever submitted,
 * commented or changed a status, who need not be allowed to read the consent page.
 */
data class IsSupportRequestSyncConsentInForce(
    val tenantKey: TenantKey,
) : Query<Boolean>,
    SystemInternal

@Component
class IsSupportRequestSyncConsentInForceHandler(
    private val jdbi: Jdbi,
) : QueryHandler<IsSupportRequestSyncConsentInForce, Boolean> {
    override fun handle(query: IsSupportRequestSyncConsentInForce): Boolean = readConsent(jdbi, query.tenantKey).inForce
}

/**
 * Whether the feedback is exchanged with Epistola support now: submitted under the consent in force
 * (`exchangedUnderConsent`). For the drivers that send a comment, a status change or an asset.
 */
data class IsSupportRequestExchangedUnderConsent(
    val id: SupportRequestId,
) : Query<Boolean>,
    SystemInternal

@Component
class IsSupportRequestExchangedUnderConsentHandler(
    private val jdbi: Jdbi,
) : QueryHandler<IsSupportRequestExchangedUnderConsent, Boolean> {
    override fun handle(query: IsSupportRequestExchangedUnderConsent): Boolean = jdbi.withHandleUnchecked { handle ->
        handle.createQuery("SELECT EXISTS (SELECT 1 FROM support_requests f WHERE f.tenant_key = :tenantKey AND f.id = :id AND ${exchangedUnderConsent("f")})")
            .bind("tenantKey", query.id.tenantKey)
            .bind("id", query.id.key.value)
            .mapTo(Boolean::class.java)
            .one()
    }
}

private fun readConsent(jdbi: Jdbi, tenantKey: TenantKey): SupportRequestSyncConsent = jdbi.withHandleUnchecked { handle ->
    handle.createQuery(SELECT_SUPPORT_REQUEST_SYNC_CONSENT)
        .bind("tenantKey", tenantKey)
        .mapTo(SupportRequestSyncConsent::class.java)
        .findOne()
        .orElse(SupportRequestSyncConsent.none(tenantKey))
}
