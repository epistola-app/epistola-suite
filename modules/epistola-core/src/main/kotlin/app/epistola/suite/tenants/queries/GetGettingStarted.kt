// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.tenants.queries

import app.epistola.suite.catalog.inCurrentVersion
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/**
 * How far a tenant is along the way from nothing to a document: a template, a published version of
 * it, that version deployed, and a document made. The tenant home walks someone new through it.
 */
data class GetGettingStarted(
    override val tenantKey: TenantKey,
) : Query<GettingStarted>,
    RequiresPermission {
    override val permission get() = Permission.CATALOG_VIEW
}

/** Each step of the way, done or not; [complete] once all are. */
data class GettingStarted(
    /** A catalog that can be changed here (one Epistola does not supply) holds a template, in the version it works in now. */
    val hasTemplate: Boolean,
    /** A catalog that can be changed here has a published version. */
    val hasPublished: Boolean,
    /** An environment serves a version of some catalog. */
    val hasDeployed: Boolean,
    /** A document was made. */
    val hasDocument: Boolean,
) {
    val complete: Boolean get() = hasTemplate && hasPublished && hasDeployed && hasDocument
}

@Component
class GetGettingStartedHandler(
    private val jdbi: Jdbi,
) : QueryHandler<GetGettingStarted, GettingStarted> {
    override fun handle(query: GetGettingStarted): GettingStarted = jdbi.withHandle<GettingStarted, Exception> { handle ->
        handle.createQuery(
            """
            SELECT
                EXISTS (SELECT 1 FROM document_templates t JOIN catalogs c ON c.tenant_key = t.tenant_key AND c.catalog_id = t.catalog_id
                        WHERE t.tenant_key = :t AND $NOT_SUPPLIED AND ${inCurrentVersion("t")}) AS has_template,
                EXISTS (SELECT 1 FROM catalog_releases r JOIN catalogs c ON c.tenant_key = r.tenant_key AND c.catalog_id = r.catalog_id
                        WHERE r.tenant_key = :t AND $NOT_SUPPLIED) AS has_published,
                EXISTS (SELECT 1 FROM environment_catalog_deployments d WHERE d.tenant_key = :t) AS has_deployed,
                EXISTS (SELECT 1 FROM documents d WHERE d.tenant_key = :t) AS has_document
            """,
        )
            .bind("t", query.tenantKey)
            .map { rs, _ ->
                GettingStarted(rs.getBoolean("has_template"), rs.getBoolean("has_published"), rs.getBoolean("has_deployed"), rs.getBoolean("has_document"))
            }
            .one()
    }
}

/** The tenant's own catalogs: every one but those Epistola supplies (ADR 0032), which it cannot change. */
private const val NOT_SUPPLIED = "COALESCE(c.source_url NOT LIKE 'classpath:%', TRUE)"
