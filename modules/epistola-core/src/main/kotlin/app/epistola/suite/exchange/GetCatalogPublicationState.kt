// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.exchange

import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.CatalogPublicationPolicy
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.catalog.queries.GetCatalogReleaseStatus
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.tenants.queries.GetTenant
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/**
 * Everything a UI needs to render publication for one catalog, already decided.
 *
 * The rules — is publishing available, may this release override the policy, what does the policy
 * default to, can the current release still be queued or retried — are domain rules, so they are
 * resolved here rather than reassembled by each surface that shows them.
 */
data class CatalogPublicationState(
    val available: Boolean,
    val policy: CatalogPublicationPolicy,
    val allowsReleaseOverride: Boolean,
    val defaultPublish: Boolean,
    val namespacePreference: String?,
    val boundNamespace: String?,
    val publications: List<CatalogReleasePublication>,
    /** True when "publish current release" is a legitimate action right now. */
    val canPublishCurrentRelease: Boolean,
    /** True when that action would retry a failed attempt rather than queue a new one. */
    val isRetry: Boolean,
) {
    val policyOptions: List<CatalogPublicationPolicy> get() = CatalogPublicationPolicy.entries
    val namespacePattern: String get() = CatalogPublicationPolicy.NAMESPACE_PATTERN
}

data class GetCatalogPublicationState(
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
) : Query<CatalogPublicationState?>,
    RequiresPermission {
    override val permission = Permission.CATALOG_VIEW
}

@Component
class GetCatalogPublicationStateHandler(
    private val jdbi: Jdbi,
    private val availability: ExchangeAvailability,
    private val namespaceBinder: ExchangeNamespaceBinder,
    private val store: CatalogPublicationStore,
) : QueryHandler<GetCatalogPublicationState, CatalogPublicationState?> {

    override fun handle(query: GetCatalogPublicationState): CatalogPublicationState? {
        val catalog = GetCatalog(query.tenantKey, query.catalogKey).query() ?: return null
        val tenant = GetTenant(query.tenantKey).query() ?: return null
        val policy = catalog.exchangePublicationPolicy
        val available = availability.isAvailable(query.tenantKey)
        // History stays visible while the feature is paused: an administrator turning it off
        // still needs to see what is queued and what already went out.
        val publications = store.list(query.tenantKey, query.catalogKey)
        val releaseStatus = GetCatalogReleaseStatus(query.tenantKey, query.catalogKey).query()
        val current = publications.firstOrNull { it.version == releaseStatus.latestVersion }
        val isRetry = current?.status == CatalogPublicationStatus.FAILED && current.archiveRetained

        return CatalogPublicationState(
            available = available,
            policy = policy,
            allowsReleaseOverride = policy.allowsReleaseOverride(),
            defaultPublish = policy.defaultPublish(tenant.publishCatalogsByDefault),
            namespacePreference = catalog.exchangeNamespacePreference,
            boundNamespace = jdbi.withHandle<String?, Exception> { handle ->
                namespaceBinder.existingBinding(handle, query.tenantKey, query.catalogKey)
            },
            publications = publications,
            canPublishCurrentRelease = available &&
                policy != CatalogPublicationPolicy.NEVER &&
                releaseStatus.latestVersion != null &&
                (isRetry || (current == null && !releaseStatus.hasUnreleasedChanges)),
            isRetry = isRetry,
        )
    }
}
