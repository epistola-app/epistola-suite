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
import app.epistola.suite.security.SecurityContext
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
    val boundNamespace: String?,
    val publications: List<CatalogReleasePublication>,
    /** True when "publish current release" is a legitimate action right now. */
    val canPublishCurrentRelease: Boolean,
    /** True when that action would retry a failed attempt rather than queue a new one. */
    val isRetry: Boolean,
    /**
     * A released version that was never published and no longer can be, because the catalog has
     * changed since it was cut.
     *
     * A release is published exactly as it was released, and Suite keeps the bytes only for
     * releases it actually queued — so once the working copy moves on, that version cannot be
     * reproduced. The action simply disappearing says none of this, which is why the state names
     * the version instead of leaving an author to wonder where the button went.
     */
    val unpublishableRelease: String?,
    /** False while the namespace is still a local choice — nothing has reached Exchange yet. */
    val namespaceLocked: Boolean,
    /** Namespaces the tenant's connection currently grants, for the namespace picker. */
    val availableNamespaces: List<String>,
    /** Whether this principal may publish this catalog at all, and therefore set its namespace. */
    val canPublish: Boolean,
    /**
     * Whether the tenant holds a usable Exchange enrollment.
     *
     * Separate from [availableNamespaces] being empty, because the two are different problems with
     * different fixes: nobody has connected this tenant yet, versus the organization it belongs to
     * has granted it nothing to publish into. Only the first is solvable inside Suite.
     */
    val connected: Boolean,
    /**
     * Where this tenant's publishing lives on Exchange, or null when it is not connected.
     *
     * Held as the organization root rather than a finished link because Suite links to submissions
     * that have no release yet — which is exactly when someone most wants to look.
     */
    val exchangeOrganizationUrl: String?,
    /**
     * The value a namespace picker should start on: the catalog's own choice once made, otherwise
     * the tenant default. The tenant default only ever pre-fills — it never binds a catalog by
     * itself, because a binding becomes permanent and a fallback should not make that decision.
     */
    val suggestedNamespace: String?,
    /**
     * Where an administrator reappoints this catalog's publisher on Exchange, with this installation
     * already proposed — or null when there is nothing to link to.
     *
     * Only meaningful alongside a [ExchangeFailureCode.CATALOG_AUTHORITY_REQUIRED] failure, and only
     * a link: the transfer is an administrator-gated action on Exchange, so following it decides
     * nothing. Suite offers the route because the refusal is discovered here, in Suite, by someone
     * who otherwise has to know that a page they have never visited exists.
     */
    val exchangeAuthorityUrl: String?,
) {
    /** True while the catalog has nowhere to publish and this principal could give it one. */
    val needsNamespaceChoice: Boolean get() = available && canPublish && boundNamespace == null

    /**
     * The catalog is bound to a namespace the connection no longer grants — an organization can
     * withdraw one, and nothing about the binding changes when it does. Nothing publishes until the
     * catalog is pointed somewhere it is still allowed, or the grant returns.
     */
    val namespaceRevoked: Boolean
        get() = available && boundNamespace != null && boundNamespace !in availableNamespaces

    /**
     * Publications that have been in flight far too long.
     *
     * The tenant-wide settings page already says this, but an author who has just published is on
     * the catalog page, where a stalled release is indistinguishable from a healthy one — it simply
     * carries an in-progress badge, for as long as it takes to give up on it.
     */
    val stalledPublications: List<CatalogReleasePublication> get() = publications.filter { it.isStalled }

    /** No namespace is available to move to, so waiting for the organization is the only option. */
    val noNamespacesGranted: Boolean get() = available && availableNamespaces.isEmpty()

    /**
     * Whether a release could actually reach Exchange right now — whether there is anywhere for it
     * to go.
     *
     * Offering to publish without this is the one combination that strands someone: the release
     * form would take the instruction, then refuse to submit because the namespace it must ask for
     * has no options, saying only "please select an item in the list". A catalog already bound to a
     * namespace the connection has since lost is the same dead end reached from the other side.
     */
    val hasPublishableDestination: Boolean
        get() = available &&
            connected &&
            if (boundNamespace != null) boundNamespace in availableNamespaces else availableNamespaces.isNotEmpty()

    /**
     * Where this publication can be inspected on Exchange, or null when there is nothing to look at.
     *
     * Deliberately the submission rather than the release: a submission page exists from the moment
     * Exchange accepts the bytes and survives every outcome, so it answers "what happened to this?"
     * for a rejected or still-undecided publication too. A release page only exists once one was
     * published, which is the case that needs explaining least.
     */
    fun exchangeUrl(publication: CatalogReleasePublication): String? = publication.remotePublicationId?.let { remote -> exchangeOrganizationUrl?.let { "$it/publishing/$remote" } }

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
    private val credentials: ExchangeCredentialService,
) : QueryHandler<GetCatalogPublicationState, CatalogPublicationState?> {

    override fun handle(query: GetCatalogPublicationState): CatalogPublicationState? {
        val catalog = GetCatalog(query.tenantKey, query.catalogKey).query() ?: return null
        val tenant = GetTenant(query.tenantKey).query() ?: return null
        val policy = catalog.exchangePublicationPolicy
        val available = availability.isAvailable(query.tenantKey)
        val canPublish = SecurityContext.current().hasPermission(query.tenantKey, Permission.CATALOG_PUBLISH)
        // History stays visible while the feature is paused: an administrator turning it off
        // still needs to see what is queued and what already went out.
        val publications = store.list(query.tenantKey, query.catalogKey)
        val releaseStatus = GetCatalogReleaseStatus(query.tenantKey, query.catalogKey).query()
        val current = publications.firstOrNull { it.version == releaseStatus.latestVersion }
        val isRetry = current?.status == CatalogPublicationStatus.FAILED && current.archiveRetained

        val connection = if (available) credentials.activeConnectionSummary(query.tenantKey) else null

        val binding = jdbi.withHandle<Binding, Exception> { handle ->
            Binding(
                namespace = namespaceBinder.existingBinding(handle, query.tenantKey, query.catalogKey),
                locked = namespaceBinder.isLocked(handle, query.tenantKey, query.catalogKey),
                granted = namespaceBinder.grantedNamespaces(handle, query.tenantKey).sorted(),
                tenantDefault = namespaceBinder.tenantDefault(handle, query.tenantKey),
            )
        }

        return CatalogPublicationState(
            available = available,
            policy = policy,
            allowsReleaseOverride = policy.allowsReleaseOverride(),
            defaultPublish = policy.defaultPublish(tenant.publishCatalogsByDefault),
            boundNamespace = binding.namespace,
            publications = publications,
            namespaceLocked = binding.locked,
            availableNamespaces = binding.granted,
            canPublish = canPublish,
            connected = available && connection != null,
            exchangeOrganizationUrl = connection?.organizationSlug
                ?.let { slug -> "${connection.baseUrl.trimEnd('/')}/organizations/$slug" },
            suggestedNamespace = binding.namespace ?: binding.tenantDefault,
            exchangeAuthorityUrl = binding.namespace?.let { namespace ->
                connection?.tenantConnectionId?.let { proposed ->
                    "${connection.baseUrl.trimEnd('/')}/catalogs/$namespace/${query.catalogKey.value}/settings?proposed=$proposed"
                }
            },
            // Nothing is queued without a destination, so the action is only offered once there is one.
            canPublishCurrentRelease = available &&
                canPublish &&
                binding.namespace != null &&
                binding.namespace in binding.granted &&
                policy != CatalogPublicationPolicy.NEVER &&
                releaseStatus.latestVersion != null &&
                (isRetry || (current == null && !releaseStatus.hasUnreleasedChanges)),
            isRetry = isRetry,
            unpublishableRelease = releaseStatus.latestVersion?.takeIf {
                available &&
                    canPublish &&
                    policy != CatalogPublicationPolicy.NEVER &&
                    current == null &&
                    releaseStatus.hasUnreleasedChanges
            },
        )
    }

    private data class Binding(
        val namespace: String?,
        val locked: Boolean,
        val granted: List<String>,
        val tenantDefault: String?,
    )
}
