// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.queries

import app.epistola.suite.catalog.CatalogEditingProperties
import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.mediator.query
import app.epistola.suite.security.SystemInternal
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/**
 * Why a catalog cannot be changed here (ADR 0032, sections 2 and 3): [label] is the short form a badge
 * or a bar shows, [explanation] the sentence a refusal or a note gives.
 */
enum class CatalogReadOnlyReason(val label: String, val explanation: String) {
    /** The installation is run-only: `epistola.catalog.editing.enabled` is off. */
    EDITING_DISABLED("This installation only runs releases", "Catalogs are not changed in this installation: it only receives and runs releases."),

    /** The suite supplies the catalog (a `classpath:` source) and replaces it on every upgrade. */
    SUPPLIED_BY_EPISTOLA("Supplied by Epistola", "Epistola supplies this catalog and replaces it on every upgrade, so it is not changed here."),
    ;

    /** As the contract names it (`CatalogReadOnlyReason`): `editing-disabled`, `supplied-by-epistola`. */
    val wire: String get() = name.lowercase().replace('_', '-')
}

/** Whether a catalog can be changed here, and if not, why. */
data class CatalogEditability(val reason: CatalogReadOnlyReason?) {
    val editable: Boolean get() = reason == null

    companion object {
        /** The rule itself: a catalog Epistola supplies is never changed, and nothing is when editing is off. */
        fun of(suppliedByEpistola: Boolean, editingEnabled: Boolean): CatalogEditability = CatalogEditability(
            when {
                suppliedByEpistola -> CatalogReadOnlyReason.SUPPLIED_BY_EPISTOLA
                !editingEnabled -> CatalogReadOnlyReason.EDITING_DISABLED
                else -> null
            },
        )
    }
}

/**
 * [CatalogEditability] for every catalog of a tenant, from one read, for pages that list resources or
 * catalogs: a list asks this once instead of once per row. By [CatalogId], since two catalogs of a
 * tenant can share a key (ADR 0034).
 */
data class CatalogEditabilities(val byCatalog: Map<CatalogId, CatalogEditability>) {
    /** The catalog's editability; null for a catalog the tenant does not have. */
    fun of(catalog: CatalogId): CatalogEditability? = byCatalog[catalog]

    /** Whether the catalog can be changed here; false for one the tenant does not have. */
    fun editable(catalog: CatalogId): Boolean = of(catalog)?.editable == true

    /** The catalogs that can be changed here. */
    val editableCatalogs: Set<CatalogId> get() = byCatalog.filterValues { it.editable }.keys

    /**
     * The editability of the catalog [address] names: `namespace/key` (ADR 0034, section 9), or a key
     * alone while one catalog has it; null when it names none.
     */
    fun of(address: String): CatalogEditability? = if ('/' in address) {
        byCatalog.entries.firstOrNull { it.key.address == address }?.value
    } else {
        byCatalog.entries.singleOrNull { it.key.key.value == address }?.value
    }
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
    val catalog: CatalogId,
) : Query<CatalogEditability>,
    SystemInternal {
    val tenantKey: TenantKey get() = catalog.tenantKey
    val catalogKey: CatalogKey get() = catalog.key
}

/** [GetCatalogEditability], as a yes or no. */
data class IsCatalogEditable(
    val catalog: CatalogId,
) : Query<Boolean>,
    SystemInternal {
    val tenantKey: TenantKey get() = catalog.tenantKey
    val catalogKey: CatalogKey get() = catalog.key
}

/**
 * [GetCatalogEditability] for every catalog of a tenant at once.
 *
 * System-internal: it says only whether each catalog can be changed, which every page showing a
 * catalog's content needs whatever the viewer's role, and nothing of what the catalogs hold.
 */
data class ListCatalogEditability(val tenantKey: TenantKey) :
    Query<CatalogEditabilities>,
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
                WHERE tenant_key = :tenantKey AND catalog_id = epistola_catalog_id(:tenantKey, :catalogKey_ns, :catalogKey)
                """,
            )
                .bind("tenantKey", query.tenantKey)
                .bindCatalog("catalogKey", query.catalog)
                .mapTo(Boolean::class.java)
                .findOne()
                .orElseThrow { IllegalArgumentException("Catalog not found: ${query.catalogKey}") }
        }
        return CatalogEditability.of(supplied, editing.enabled)
    }
}

@Component
class ListCatalogEditabilityHandler(
    private val jdbi: Jdbi,
    private val editing: CatalogEditingProperties,
) : QueryHandler<ListCatalogEditability, CatalogEditabilities> {

    override fun handle(query: ListCatalogEditability): CatalogEditabilities = jdbi.withHandle<CatalogEditabilities, Exception> { handle ->
        val supplied = handle.createQuery(
            """
            SELECT namespace, catalog_key, COALESCE(source_url LIKE 'classpath:%', FALSE) AS supplied
            FROM catalogs
            WHERE tenant_key = :tenantKey
            """,
        )
            .bind("tenantKey", query.tenantKey)
            .map { rs, _ -> CatalogId(rs.getString("namespace"), CatalogKey.of(rs.getString("catalog_key")), app.epistola.suite.common.ids.TenantId(query.tenantKey)) to rs.getBoolean("supplied") }
            .list()
        CatalogEditabilities(supplied.associate { (key, isSupplied) -> key to CatalogEditability.of(isSupplied, editing.enabled) })
    }
}

@Component
class IsCatalogEditableHandler : QueryHandler<IsCatalogEditable, Boolean> {
    override fun handle(query: IsCatalogEditable): Boolean = GetCatalogEditability(query.catalog).query().editable
}
