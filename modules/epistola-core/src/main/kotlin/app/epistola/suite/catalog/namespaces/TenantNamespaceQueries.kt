// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.namespaces

import app.epistola.suite.catalog.CatalogIdentityProperties
import app.epistola.suite.catalog.CatalogNotFoundException
import app.epistola.suite.catalog.CatalogReleasePublicationPort
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Query
import app.epistola.suite.mediator.QueryHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.PlatformRole
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.security.RequiresPlatformRole
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.Jdbi
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component

/** Every namespace a tenant holds, with how, when and by whom: what a platform administrator manages. */
data class ListNamespaceGrants(val tenantKey: TenantKey) :
    Query<List<NamespaceGrant>>,
    RequiresPlatformRole {
    override val platformRole = PlatformRole.TENANT_MANAGER
}

/**
 * The namespaces a tenant may name a new catalog in: those it holds, and `local` with any namespace
 * it picks. What the create dialog, MCP and a refusal offer.
 */
data class ListHeldNamespaces(override val tenantKey: TenantKey) :
    Query<HeldNamespaces>,
    RequiresPermission {
    override val permission get() = Permission.CATALOG_VIEW
}

/**
 * The namespaces a tenant holds ([held]), the one a new catalog starts in when nobody chooses
 * ([choice]), and the local namespaces its catalogs use already ([localInUse]).
 */
data class HeldNamespaces(
    val held: List<NamespaceName>,
    /** The publishing integration's preferred namespace (the Exchange default), when the tenant holds it. */
    val preferred: NamespaceName?,
    val localInUse: List<String>,
) {
    /** What a new catalog is named in when nobody chooses: the one held, or the preferred of several; null when neither. */
    val choice: NamespaceName? get() = held.singleOrNull() ?: preferred

    /** The local namespace to suggest: the one the tenant's catalogs use most, or [LocalAuthority.DEFAULT_NAMESPACE]. */
    val suggestedLocalNamespace: String get() = localInUse.firstOrNull() ?: LocalAuthority.DEFAULT_NAMESPACE
}

/** Whether the tenant holds the namespace a catalog is named in, so that it may release it (ADR 0034, section 2). */
data class GetCatalogNamespaceHolding(
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
) : Query<CatalogNamespaceHolding>,
    RequiresPermission {
    override val permission get() = Permission.CATALOG_VIEW
}

/** The catalog's [name] and whether its tenant holds it; a local name always is. */
data class CatalogNamespaceHolding(val name: NamespaceName, val held: Boolean) {
    /** Why releasing it is refused, in a sentence; null when it is not. */
    val refusal: String? get() = if (held) null else NamespaceMessages.notHeld(name)
}

@Component
class ListNamespaceGrantsHandler(
    private val jdbi: Jdbi,
    private val store: NamespaceGrantStore,
) : QueryHandler<ListNamespaceGrants, List<NamespaceGrant>> {
    override fun handle(query: ListNamespaceGrants): List<NamespaceGrant> = jdbi.withHandle<List<NamespaceGrant>, Exception> { store.grants(it, query.tenantKey) }
}

@Component
class ListHeldNamespacesHandler(
    private val jdbi: Jdbi,
    private val store: NamespaceGrantStore,
    private val identity: CatalogIdentityProperties,
    private val publicationPort: ObjectProvider<CatalogReleasePublicationPort>,
) : QueryHandler<ListHeldNamespaces, HeldNamespaces> {
    override fun handle(query: ListHeldNamespaces): HeldNamespaces = jdbi.withHandle<HeldNamespaces, Exception> { handle ->
        heldNamespaces(handle, query.tenantKey, store, identity, publicationPort.ifAvailable)
    }
}

/** [HeldNamespaces] on [handle], for the query and for [app.epistola.suite.catalog.commands.CreateCatalog]. */
fun heldNamespaces(
    handle: Handle,
    tenantKey: TenantKey,
    store: NamespaceGrantStore,
    identity: CatalogIdentityProperties,
    port: CatalogReleasePublicationPort?,
): HeldNamespaces {
    val held = store.grants(handle, tenantKey).map { it.name }
    val preferred = port?.preferredNamespace(handle, tenantKey)
        ?.let { NamespaceName(identity.defaultRegistry, it) }
        ?.takeIf { it in held }
    return HeldNamespaces(held, preferred, store.localNamespacesInUse(handle, tenantKey))
}

@Component
class GetCatalogNamespaceHoldingHandler(
    private val jdbi: Jdbi,
    private val store: NamespaceGrantStore,
) : QueryHandler<GetCatalogNamespaceHolding, CatalogNamespaceHolding> {
    override fun handle(query: GetCatalogNamespaceHolding): CatalogNamespaceHolding = jdbi.withHandle<CatalogNamespaceHolding, Exception> { handle ->
        holdingOf(handle, store, query.tenantKey, query.catalogKey)
    }
}

/** Whether the tenant holds [catalogKey]'s namespace, on [handle]. */
fun holdingOf(handle: Handle, store: NamespaceGrantStore, tenantKey: TenantKey, catalogKey: CatalogKey): CatalogNamespaceHolding {
    val name = handle.createQuery("SELECT registry, namespace FROM catalogs WHERE tenant_key = :tenantKey AND catalog_id = epistola_catalog_id(:tenantKey, :catalogKey)")
        .bind("tenantKey", tenantKey).bind("catalogKey", catalogKey)
        .map { rs, _ -> NamespaceName(rs.getString("registry"), rs.getString("namespace")) }
        .findOne().orElseThrow { CatalogNotFoundException(catalogKey) }
    return CatalogNamespaceHolding(name, store.holds(handle, tenantKey, name))
}

/**
 * The namespaces in which the tenant has a catalog with [catalogKey] (ADR 0034, phase 4): one for a
 * key that names one catalog, several when catalogs of different namespaces share it, none when the
 * tenant has no such catalog. What an address naming a catalog by key alone is checked against.
 */
data class ListCatalogKeyNamespaces(
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
) : Query<CatalogKeyNamespaces>,
    RequiresPermission {
    override val permission get() = Permission.CATALOG_VIEW
}

/** The namespaces a catalog key is used in, by name. */
data class CatalogKeyNamespaces(val namespaces: List<String>) {
    /** The one namespace the key names a catalog in; null when it names none or several. */
    val single: String? get() = namespaces.singleOrNull()
}

@Component
class ListCatalogKeyNamespacesHandler(private val jdbi: Jdbi) : QueryHandler<ListCatalogKeyNamespaces, CatalogKeyNamespaces> {
    override fun handle(query: ListCatalogKeyNamespaces): CatalogKeyNamespaces = jdbi.withHandle<CatalogKeyNamespaces, Exception> { handle ->
        CatalogKeyNamespaces(
            handle.createQuery("SELECT namespace FROM catalogs WHERE tenant_key = :t AND catalog_key = :k ORDER BY namespace")
                .bind("t", query.tenantKey).bind("k", query.catalogKey)
                .mapTo(String::class.java).list(),
        )
    }
}
