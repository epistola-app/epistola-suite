// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.commands

import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.CatalogNotFoundException
import app.epistola.suite.catalog.CatalogUpstreamCheck
import app.epistola.suite.catalog.CatalogUpstreamCheckException
import app.epistola.suite.catalog.CatalogUpstreamCheckFailure
import app.epistola.suite.catalog.CatalogUpstreamCheckProperties
import app.epistola.suite.catalog.CatalogUpstreamCheckStore
import app.epistola.suite.catalog.CatalogUpstreamProbe
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.SelfManagedTransaction
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.validation.ValidationCode
import app.epistola.suite.validation.ValidationException
import org.springframework.stereotype.Component

/**
 * Asks a subscribed catalog's source, right now, whether it has published anything newer.
 *
 * The "check for updates" button. The background worker does the same thing on a schedule; this
 * exists because a reader who has just been told a release exists should not have to wait up to six
 * hours to see it, and because a failed check is worth being able to retry deliberately.
 *
 * A **command** rather than a query, despite reading like one: it writes what it learned, and a
 * query that writes is a query nobody can reason about. The permission stays
 * [Permission.CATALOG_VIEW] — asking a source what it publishes is not managing anything, and the
 * button sits on a page anyone who can see catalogs can open.
 *
 * `SelfManagedTransaction` because the source is called mid-command over the network.
 */
data class CheckCatalogUpstream(
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
) : Command<CatalogUpstreamCheck?>,
    RequiresPermission,
    SelfManagedTransaction {
    override val permission get() = Permission.CATALOG_VIEW
}

@Component
class CheckCatalogUpstreamHandler(
    private val store: CatalogUpstreamCheckStore,
    private val probes: List<CatalogUpstreamProbe>,
    private val properties: CatalogUpstreamCheckProperties,
) : CommandHandler<CheckCatalogUpstream, CatalogUpstreamCheck?> {

    override fun handle(command: CheckCatalogUpstream): CatalogUpstreamCheck? {
        val catalog = GetCatalog(command.tenantKey, command.catalogKey).query()
            ?: throw CatalogNotFoundException(command.catalogKey)
        if (catalog.sourceUrl == null) {
            // A catalog imported from a ZIP has no upstream to ask. Not an error — there is simply
            // nothing to check, and the UI shows it as managed by whoever uploads the archive.
            return null
        }
        val probe = probes.firstOrNull { it.supports(catalog) }
            ?: throw ValidationException(
                "sourceUrl",
                "Nothing in this version of Epistola can read a catalog source at '${catalog.sourceUrl}'.",
                ValidationCode.GENERIC,
            )

        // The row may not exist yet: catalogs subscribed before this feature, and any created by a
        // path that does not record an install, are only tracked once somebody asks.
        store.ensureTracked(command.tenantKey, command.catalogKey)
        try {
            store.recordSuccess(command.tenantKey, command.catalogKey, probe.probe(catalog), properties.interval)
        } catch (e: CatalogUpstreamCheckException) {
            store.recordFailure(command.tenantKey, command.catalogKey, e.failure, e.detail, properties.interval)
        } catch (e: Exception) {
            store.recordFailure(
                command.tenantKey,
                command.catalogKey,
                CatalogUpstreamCheckFailure.PROTOCOL_ERROR,
                e.message,
                properties.interval,
            )
        }
        return store.stateFor(command.tenantKey, command.catalogKey)
    }
}
