// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.namespaces

import app.epistola.catalog.protocol.CatalogNamespaces
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.UserKey
import app.epistola.suite.time.EpistolaClock
import org.jdbi.v3.core.Handle
import org.springframework.stereotype.Component
import java.time.OffsetDateTime
import java.util.UUID

/**
 * A namespace as a tenant holds it (ADR 0034, section 3): the [authority] it is granted under, the
 * contract's `registry` member (`exchange.epistola.app`, an organisation's own domain, or
 * [LocalAuthority.NAME]), and the [namespace] under it. Written `authority/namespace`.
 */
data class NamespaceName(val authority: String, val namespace: String) {
    /** Named under `local`: needs no grant, and never leaves the organisation (ADR 0034, section 7). */
    val isLocal: Boolean get() = LocalAuthority.isLocal(authority)

    override fun toString(): String = "$authority/$namespace"

    companion object {
        /** `authority/namespace`, as [toString] writes it and a form posts it; null when it is not one. */
        fun parse(value: String?): NamespaceName? {
            val trimmed = value?.trim().orEmpty()
            val authority = trimmed.substringBeforeLast('/', "")
            val namespace = trimmed.substringAfterLast('/', "")
            return if (authority.isEmpty() || namespace.isEmpty()) null else NamespaceName(authority, namespace)
        }
    }
}

/**
 * The authority every installation has (ADR 0034, section 7): `local/hr/letters` is a name that is
 * valid within the organisation and never published outside it. A tenant names catalogs under it
 * with any namespace it picks, without a grant, since nothing outside depends on it.
 *
 * The contract carries it (catalog format and API), so a local catalog is exported, snapshotted and
 * moved through the organisation's own container registries like any other; only the Exchange, which
 * is outside the organisation, refuses it.
 */
object LocalAuthority {
    const val NAME: String = "local"

    /**
     * The local namespace a catalog gets when nobody chose one: only the default catalog of a tenant
     * created without a namespace. Not derived from the tenant key (ADR 0034, section 3).
     */
    const val DEFAULT_NAMESPACE: String = "default"

    fun isLocal(authority: String?): Boolean = authority == NAME

    /** An authority this installation names catalogs under: a domain, as the contract writes one, or `local`. */
    fun isValidAuthority(authority: String?): Boolean = authority != null && (isLocal(authority) || CatalogNamespaces.isValidRegistry(authority))
}

/** How a tenant came to hold a namespace (`namespace_grant_sources`). */
enum class NamespaceGrantSource(val label: String) {
    /** The tenant's Epistola Exchange connection grants it. */
    EXCHANGE_CONNECTION("Exchange connection"),

    /** A platform administrator granted it, or it was granted when the tenant was created. */
    ADMINISTRATOR("Administrator"),

    /** Held since before namespaces were granted: catalogs the tenant authored were named in it. */
    EXISTING("Held before grants"),
}

/** One namespace a tenant holds, how and when it came to, and who granted it when a person did. */
data class NamespaceGrant(
    val tenantKey: TenantKey,
    val authority: String,
    val namespace: String,
    val grantedThrough: NamespaceGrantSource,
    val grantedAt: OffsetDateTime,
    val grantedBy: UserKey?,
    /** The display name of [grantedBy], when that user is known here. */
    val grantedByName: String?,
) {
    val name: NamespaceName get() = NamespaceName(authority, namespace)
}

/** What recording a grant did. */
sealed interface GrantOutcome {
    /** The tenant holds it now, and did not before. */
    data object Granted : GrantOutcome

    /** The tenant held it already; the earlier grant stands. */
    data object AlreadyHeld : GrantOutcome

    /** The tenant holds the same namespace under [authority]: two authorities for one token are refused (ADR 0034, section 2). */
    data class TakenUnder(val authority: String) : GrantOutcome
}

/**
 * The SQL of `tenant_namespace_grants`, and the one answer to "may this tenant name catalogs in, and
 * release under, this namespace?" ([holds]).
 */
@Component
class NamespaceGrantStore {

    /** The tenant's grants, by authority and namespace. */
    fun grants(handle: Handle, tenantKey: TenantKey): List<NamespaceGrant> = handle.createQuery(
        """
        SELECT g.tenant_key, g.authority, g.namespace, g.granted_through, g.granted_at, g.granted_by, u.display_name
        FROM tenant_namespace_grants g
        LEFT JOIN users u ON u.id = g.granted_by
        WHERE g.tenant_key = :tenantKey
        ORDER BY g.namespace, g.authority
        """,
    ).bind("tenantKey", tenantKey)
        .map { rs, _ ->
            NamespaceGrant(
                tenantKey = TenantKey.of(rs.getString("tenant_key")),
                authority = rs.getString("authority"),
                namespace = rs.getString("namespace"),
                grantedThrough = NamespaceGrantSource.valueOf(rs.getString("granted_through")),
                grantedAt = rs.getObject("granted_at", OffsetDateTime::class.java),
                grantedBy = rs.getObject("granted_by", UUID::class.java)?.let(UserKey::of),
                grantedByName = rs.getString("display_name"),
            )
        }.list()

    /** Whether the tenant may name catalogs in [name] and release under it: it holds it, or it is local. */
    fun holds(handle: Handle, tenantKey: TenantKey, name: NamespaceName): Boolean = name.isLocal ||
        handle.createQuery(
            "SELECT EXISTS (SELECT 1 FROM tenant_namespace_grants WHERE tenant_key = :tenantKey AND authority = :authority AND namespace = :namespace)",
        ).bind("tenantKey", tenantKey).bind("authority", name.authority).bind("namespace", name.namespace)
            .mapTo(Boolean::class.java).one()

    /** The authority under which the tenant holds the namespace token [namespace], or null when it holds it under none. */
    fun authorityOf(handle: Handle, tenantKey: TenantKey, namespace: String): String? = handle.createQuery(
        "SELECT authority FROM tenant_namespace_grants WHERE tenant_key = :tenantKey AND namespace = :namespace ORDER BY granted_at LIMIT 1",
    ).bind("tenantKey", tenantKey).bind("namespace", namespace)
        .mapTo(String::class.java).findOne().orElse(null)

    /**
     * Records that the tenant holds [name], unless it does already, or holds the same namespace
     * under another authority. Never for a local name, which needs no grant.
     */
    fun grant(handle: Handle, tenantKey: TenantKey, name: NamespaceName, source: NamespaceGrantSource, grantedBy: UserKey?): GrantOutcome {
        require(!name.isLocal) { "A local namespace needs no grant" }
        authorityOf(handle, tenantKey, name.namespace)?.let { held ->
            return if (held == name.authority) GrantOutcome.AlreadyHeld else GrantOutcome.TakenUnder(held)
        }
        val inserted = handle.createUpdate(
            """
            INSERT INTO tenant_namespace_grants (tenant_key, authority, namespace, granted_through, granted_at, granted_by)
            VALUES (:tenantKey, :authority, :namespace, :source, :grantedAt, :grantedBy)
            ON CONFLICT DO NOTHING
            """,
        ).bind("tenantKey", tenantKey).bind("authority", name.authority).bind("namespace", name.namespace)
            .bind("source", source.name).bind("grantedAt", EpistolaClock.offsetDateTime()).bind("grantedBy", grantedBy?.value)
            .execute()
        return if (inserted == 1) GrantOutcome.Granted else GrantOutcome.AlreadyHeld
    }

    /** Removes the grant; false when the tenant did not hold [name]. */
    fun revoke(handle: Handle, tenantKey: TenantKey, name: NamespaceName): Boolean = handle.createUpdate(
        "DELETE FROM tenant_namespace_grants WHERE tenant_key = :tenantKey AND authority = :authority AND namespace = :namespace",
    ).bind("tenantKey", tenantKey).bind("authority", name.authority).bind("namespace", name.namespace).execute() == 1

    /**
     * The tenant's catalogs named in [name] that it authors, and could release: not supplied by
     * Epistola and not received from a source (ADR 0032, section 2). These keep a namespace from
     * being revoked.
     */
    fun authoredCatalogsIn(handle: Handle, tenantKey: TenantKey, name: NamespaceName): List<CatalogKey> = handle.createQuery(
        """
        SELECT catalog_key FROM catalogs
        WHERE tenant_key = :tenantKey AND registry = :authority AND namespace = :namespace AND source_url IS NULL
        ORDER BY catalog_key
        """,
    ).bind("tenantKey", tenantKey).bind("authority", name.authority).bind("namespace", name.namespace)
        .mapTo(String::class.java).list().map(CatalogKey::of)

    /** The local namespaces the tenant's catalogs are named in already, most used first. */
    fun localNamespacesInUse(handle: Handle, tenantKey: TenantKey): List<String> = handle.createQuery(
        """
        SELECT namespace FROM catalogs WHERE tenant_key = :tenantKey AND registry = :local
        GROUP BY namespace ORDER BY count(*) DESC, namespace
        """,
    ).bind("tenantKey", tenantKey).bind("local", LocalAuthority.NAME)
        .mapTo(String::class.java).list()
}

/** The sentences a refusal or a withheld action says about namespaces, in one place. */
object NamespaceMessages {
    /** Why a catalog named in [name] cannot be created, or released, by this tenant. */
    fun notHeld(name: NamespaceName): String = "This tenant does not hold the namespace ${name.namespace} (${name.authority}); an administrator can grant it."

    /** Why a catalog named under local is not published to Epistola Exchange: local never leaves the organisation. */
    fun notPublished(catalog: String): String = "$catalog is named under local, which never leaves this organisation, so it is not published to Epistola Exchange."
}
