// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.queries

import app.epistola.suite.catalog.revisions.ReleaseEntryStore
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/**
 * The versions of a catalog that can be handed over exactly as released, newest first.
 *
 * A release retains its content only from `V20260923201010`; older ones are a fingerprint and a
 * promise, and there is no way to reconstruct them. This is the single definition of "which
 * releases can be read back", so an offer to export one and the export itself cannot disagree —
 * the read model owns the predicate and the screen gates on it.
 */
data class ListRetainedReleases(
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
) : Query<List<String>>,
    RequiresPermission {
    override val permission get() = Permission.CATALOG_VIEW
}

@Component
class ListRetainedReleasesHandler(
    private val jdbi: Jdbi,
    private val releaseEntryStore: ReleaseEntryStore,
) : QueryHandler<ListRetainedReleases, List<String>> {

    override fun handle(query: ListRetainedReleases): List<String> = jdbi.withHandle<List<String>, Exception> { handle ->
        releaseEntryStore.retainedVersions(handle, query.tenantKey, query.catalogKey)
    }
}
