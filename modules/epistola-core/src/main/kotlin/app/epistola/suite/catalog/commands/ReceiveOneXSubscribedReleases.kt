// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.commands

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
            runCatching { SecurityContext.runWithPrincipal(SystemUser.principalForTenant(command.tenantKey)) { UpgradeCatalog(catalog).execute() } }
                .onSuccess { received += catalog }
                .onFailure {
                    failed[catalog] = it.message ?: it::class.simpleName.orEmpty()
                    log.warn("Catalog {} of tenant {} did not receive its release from its source, will retry at the next boot: {}", catalog.address, command.tenantKey.value, it.message)
                }
        }
        return ReceivedOneXSubscribedReleases(received, failed)
    }
}
