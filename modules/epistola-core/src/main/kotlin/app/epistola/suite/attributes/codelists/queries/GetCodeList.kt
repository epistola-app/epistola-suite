// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.attributes.codelists.queries

import app.epistola.suite.attributes.codelists.CodeListStore
import app.epistola.suite.attributes.codelists.model.CodeList
import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.CatalogReleaseScoped
import app.epistola.suite.catalog.releases.ReleaseReadable
import app.epistola.suite.catalog.versionAt
import app.epistola.suite.common.ids.CodeListId
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

data class GetCodeList(
    val id: CodeListId,
    /** The catalog version this addresses; null for its catalog's current version. */
    override val catalogVersion: CatalogReleaseRef? = null,
) : ReleaseReadable,
    Query<CodeList?>,
    RequiresPermission {
    override val permission get() = Permission.REFERENCE_VIEW
    override val tenantKey get() = id.tenantKey
}

@Component
class GetCodeListHandler(
    private val codeLists: CodeListStore,
    private val jdbi: Jdbi,
) : QueryHandler<GetCodeList, CodeList?> {
    // A draft or a release, read the same way (ADR 0033).
    override fun handle(query: GetCodeList): CodeList? = jdbi.withHandle<CodeList?, Exception> { codeLists.get(it, query.id) }
}
