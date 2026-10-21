// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.queries

import app.epistola.suite.catalog.CatalogEditingProperties
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.mediator.query
import app.epistola.suite.security.SystemInternal
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/** Why a catalog cannot be changed here (ADR 0032, sections 2 and 3). */
enum class CatalogReadOnlyReason(val explanation: String) {
    /** The installation is run-only: `epistola.catalog.editing.enabled` is off. */
    EDITING_DISABLED("Catalogs are not changed in this installation: it only receives and runs releases."),

    /** The suite supplies the catalog (a `classpath:` source) and replaces it on every upgrade. */
    SUPPLIED_BY_EPISTOLA("Epistola supplies this catalog and replaces it on every upgrade, so it is not changed here."),
}

/** Whether a catalog can be changed here, and if not, why. */
data class CatalogEditability(val reason: CatalogReadOnlyReason?) {
    val editable: Boolean get() = reason == null
}

/**
 * Whether a catalog can be changed in this installation (ADR 0032): editing is switched on, and the
 * catalog is not one the suite supplies. Where its releases come from does not matter: a catalog
 * received from elsewhere can be changed, and what it receives later is merged into its next version.
 * Throws if the catalog does not exist.
 *
 * System-internal: called from within command handlers that already have authorization.
 */
data class GetCatalogEditability(
    val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
) : Query<CatalogEditability>,
    SystemInternal

/** [GetCatalogEditability], as a yes or no. */
data class IsCatalogEditable(
    val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
) : Query<Boolean>,
    SystemInternal

@Component
class GetCatalogEditabilityHandler(
    private val jdbi: Jdbi,
    private val editing: CatalogEditingProperties,
) : QueryHandler<GetCatalogEditability, CatalogEditability> {

    override fun handle(query: GetCatalogEditability): CatalogEditability {
        val supplied = jdbi.withHandle<Boolean, Exception> { handle ->
            handle.createQuery(
                """
                SELECT COALESCE(source_url LIKE 'classpath:%', FALSE)
                FROM catalogs
                WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey
                """,
            )
                .bind("tenantKey", query.tenantKey)
                .bind("catalogKey", query.catalogKey)
                .mapTo(Boolean::class.java)
                .findOne()
                .orElseThrow { IllegalArgumentException("Catalog not found: ${query.catalogKey}") }
        }
        return CatalogEditability(
            when {
                supplied -> CatalogReadOnlyReason.SUPPLIED_BY_EPISTOLA
                !editing.enabled -> CatalogReadOnlyReason.EDITING_DISABLED
                else -> null
            },
        )
    }
}

@Component
class IsCatalogEditableHandler : QueryHandler<IsCatalogEditable, Boolean> {
    override fun handle(query: IsCatalogEditable): Boolean = GetCatalogEditability(query.tenantKey, query.catalogKey).query().editable
}
