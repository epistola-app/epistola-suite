// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.commands

import app.epistola.suite.catalog.Catalog
import app.epistola.suite.catalog.CatalogClient
import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.findCatalog
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.SelfManagedTransaction
import app.epistola.suite.mediator.execute
import app.epistola.suite.security.SecurityContext
import app.epistola.suite.security.SystemInternal
import app.epistola.suite.security.SystemUser
import org.jdbi.v3.core.Jdbi
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Gives each catalog a 1.x installation subscribed to from a source URL the release it receives from
 * that source (ADR 0035): the 2.0 upgrade cuts no release, so such a catalog has its 1.x content as
 * its next version and no release, until its source's release is installed, through the one install
 * core ([UpgradeCatalog]: installing is importing). Its next version names the release 1.x had
 * installed as its base, which kept no content, so the release fast-forwards it.
 *
 * Only catalogs 1.x subscribed to (`catalogs.type`, read only for the upgrade) and only while they
 * have received nothing: a catalog registered under 2.0 is installed when someone installs it. The
 * catalogs Epistola supplies from the classpath are installed by their own loaders. A source that
 * cannot be reached is logged and tried again at the next boot.
 *
 * 1.x catalogs had no namespace: the upgrade named a subscribed one in its tenant's (the tenant key,
 * `V20261101000100`), while its source names it as its publisher does. Before installing, a catalog
 * that has never had a release takes the registry and namespace its source's manifest gives it (2.0
 * keeps a namespace changeable until a release is published under it), together with the drafts that
 * declare it; otherwise the install would receive the release as another catalog, a second one, and
 * leave this one without. A source naming another key, or a name another catalog of the tenant
 * already has, is a failure, logged and retried.
 *
 * `SystemInternal`: run by the boot pass for every tenant, as that tenant's system principal.
 */
data class ReceiveOneXSubscribedReleases(
    val tenantKey: TenantKey,
) : Command<ReceivedOneXSubscribedReleases>,
    SystemInternal,
    // Each install fetches its source over HTTP and runs in its own transactions.
    SelfManagedTransaction

/** The catalogs that received their release, and those whose source failed, with why. */
data class ReceivedOneXSubscribedReleases(val received: List<CatalogId>, val failed: Map<CatalogId, String>)

@Component
class ReceiveOneXSubscribedReleasesHandler(
    private val jdbi: Jdbi,
    private val client: CatalogClient,
) : CommandHandler<ReceiveOneXSubscribedReleases, ReceivedOneXSubscribedReleases> {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun handle(command: ReceiveOneXSubscribedReleases): ReceivedOneXSubscribedReleases {
        val waiting = jdbi.withHandle<List<CatalogId>, Exception> { handle ->
            handle.createQuery(
                """
                SELECT c.namespace, c.catalog_key FROM catalogs c
                WHERE c.tenant_key = :t AND c.type = 'SUBSCRIBED' AND c.source_url IS NOT NULL AND c.source_url NOT LIKE 'classpath:%'
                  AND NOT EXISTS (SELECT 1 FROM catalog_versions r WHERE r.tenant_key = c.tenant_key AND r.catalog_id = c.catalog_id AND r.state = 'released')
                ORDER BY c.catalog_key, c.namespace
                """,
            )
                .bind("t", command.tenantKey)
                .map { rs, _ -> CatalogId(rs.getString(1), CatalogKey.of(rs.getString(2)), TenantId(command.tenantKey)) }
                .list()
        }
        val received = mutableListOf<CatalogId>()
        val failed = linkedMapOf<CatalogId, String>()
        for (catalog in waiting) {
            runCatching {
                val named = namedAsItsSource(catalog)
                SecurityContext.runWithPrincipal(SystemUser.principalForTenant(command.tenantKey)) { UpgradeCatalog(named).execute() }
                named
            }
                .onSuccess { received += it }
                .onFailure {
                    failed[catalog] = it.message ?: it::class.simpleName.orEmpty()
                    log.warn("Catalog {} of tenant {} did not receive its release from its source, will retry at the next boot: {}", catalog.address, command.tenantKey.value, it.message)
                }
        }
        return ReceivedOneXSubscribedReleases(received, failed)
    }

    /**
     * [catalog] under the registry and namespace its source names it by, renamed to them first when
     * they differ: see the class.
     */
    private fun namedAsItsSource(catalog: CatalogId): CatalogId {
        val current = jdbi.withHandle<Catalog?, Exception> { it.findCatalog(catalog) } ?: return catalog
        val named = client.fetchManifest(requireNotNull(current.sourceUrl), current.sourceAuthType, current.sourceAuthCredential?.value).catalog
        check(named.key == catalog.key.value) { "its source names the catalog ${named.key}, not ${catalog.key.value}" }
        // A source of a wire version before names (v7 and older) names no namespace: the catalog keeps its own.
        val namespace = named.namespace ?: return catalog
        val registry = named.registry ?: current.registry
        if (registry == current.registry && namespace == current.namespace) return catalog
        jdbi.useTransaction<Exception> { handle ->
            val taken = handle.createQuery("SELECT EXISTS (SELECT 1 FROM catalogs WHERE tenant_key = :t AND registry = :r AND namespace = :n AND catalog_key = :k)")
                .bind("t", catalog.tenantKey)
                .bind("r", registry)
                .bind("n", namespace)
                .bind("k", catalog.key.value)
                .mapTo(Boolean::class.java)
                .one()
            check(!taken) { "another catalog of the tenant is already $registry/$namespace/${named.key}, the name its source gives it" }
            handle.createUpdate("UPDATE catalogs SET registry = :r, namespace = :n, updated_at = NOW() WHERE tenant_key = :t AND catalog_id = ${catalogAt("t", "c")}")
                .bind("r", registry)
                .bind("n", namespace)
                .bind("t", catalog.tenantKey)
                .bindCatalog("c", catalog)
                .execute()
            // The tenant's drafts that declare it follow it, as a namespace change does.
            handle.createUpdate(
                """
                UPDATE catalog_version_dependencies SET registry = :r, namespace = :n
                WHERE tenant_key = :t AND registry = :previousRegistry AND namespace = :previous AND dependency_key = :k
                  AND version_id IN (SELECT version_id FROM catalog_versions WHERE tenant_key = :t AND state = 'draft')
                """,
            )
                .bind("r", registry)
                .bind("n", namespace)
                .bind("t", catalog.tenantKey)
                .bind("previousRegistry", current.registry)
                .bind("previous", current.namespace)
                .bind("k", catalog.key.value)
                .execute()
        }
        log.info("Catalog {} of tenant {} takes the name its source gives it, {}/{}", catalog.address, catalog.tenantKey.value, registry, namespace)
        return catalog.copy(namespace = namespace)
    }
}
