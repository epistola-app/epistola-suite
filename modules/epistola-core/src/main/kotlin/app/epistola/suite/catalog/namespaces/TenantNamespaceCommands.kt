// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.namespaces

import app.epistola.catalog.protocol.CatalogNamespaces
import app.epistola.suite.catalog.CatalogIdentityProperties
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.security.SystemInternal
import app.epistola.suite.security.currentUserIdOrNull
import app.epistola.suite.tenants.TenantNotFoundException
import app.epistola.suite.validation.ValidationCode
import app.epistola.suite.validation.ValidationException
import app.epistola.suite.validation.validate
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/**
 * The tenant's administrator adds a namespace under a domain the tenant is connected to, usually the
 * organisation's own (ADR 0034, amendment of 2026-11-03): adding a domain's first namespace connects
 * the tenant to that domain. Several tenants may hold one namespace, and a tenant several. Adding one
 * the tenant holds already changes nothing; one it holds under another authority is refused, so a
 * namespace token names one namespace in a tenant (ADR 0034, section 2). `local` needs no grant, and
 * the Exchange's namespaces come only from the Exchange connection; both are refused here.
 *
 * Needs [Permission.INTEGRATIONS_MANAGE], the permission of the Exchange connection: both are the
 * tenant's connections to an authority.
 */
data class GrantTenantNamespace(
    override val tenantKey: TenantKey,
    val authority: String,
    val namespace: String,
) : Command<NamespaceGrant>,
    RequiresPermission {
    override val permission get() = Permission.INTEGRATIONS_MANAGE

    init {
        validateNamespaceName(authority, namespace)
        validate("authority", !LocalAuthority.isLocal(authority), ValidationCode.CATALOG_NAMESPACE_INVALID) {
            "Every tenant may name catalogs under local without a grant; add a namespace under a domain."
        }
    }
}

/**
 * Records that a tenant holds a namespace under any authority, the Exchange's included, as
 * [app.epistola.suite.tenants.commands.CreateTenant]'s `namespaces` do for a new tenant: for
 * bootstrap, the demo and tests, which stand in for an authority that is not there.
 *
 * `SystemInternal`: no person issues it and no route reaches it. A person adds a domain's namespace
 * with [GrantTenantNamespace]; an Exchange namespace comes from the Exchange connection.
 */
data class SeedTenantNamespace(
    val tenantKey: TenantKey,
    val authority: String,
    val namespace: String,
) : Command<NamespaceGrant>,
    SystemInternal {
    init {
        validateNamespaceName(authority, namespace)
        validate("authority", !LocalAuthority.isLocal(authority), ValidationCode.CATALOG_NAMESPACE_INVALID) {
            "A local namespace needs no grant."
        }
    }
}

/**
 * The tenant's administrator removes a namespace a domain grants it. Refused while catalogs the
 * tenant authors are named in it, which are named: they would be left unable to release, so they are
 * moved or deleted first. Catalogs it received or that Epistola supplies do not count: receiving
 * needs no grant. One the Exchange connection grants is refused: it goes with the connection.
 */
data class RevokeTenantNamespace(
    override val tenantKey: TenantKey,
    val authority: String,
    val namespace: String,
) : Command<NamespaceRevocation>,
    RequiresPermission {
    override val permission get() = Permission.INTEGRATIONS_MANAGE
}

/** What revoking did: [revoked] is false when the tenant did not hold [name]. */
data class NamespaceRevocation(val name: NamespaceName, val revoked: Boolean)

/** The rules every namespace name follows: the contract's for the namespace, a domain or `local` for the authority. */
internal fun validateNamespaceName(authority: String, namespace: String) {
    validate("authority", LocalAuthority.isValidAuthority(authority), ValidationCode.CATALOG_NAMESPACE_INVALID) {
        "The authority must be a domain, such as gemeente-x.nl: lowercase labels joined by dots."
    }
    validate("namespace", CatalogNamespaces.isValid(namespace), ValidationCode.CATALOG_NAMESPACE_INVALID) {
        "The namespace must be a lowercase slug of ${CatalogNamespaces.MIN_LENGTH} to ${CatalogNamespaces.MAX_LENGTH} characters: letters, digits and single hyphens, starting with a letter."
    }
}

@Component
class GrantTenantNamespaceHandler(
    private val jdbi: Jdbi,
    private val store: NamespaceGrantStore,
    private val identity: CatalogIdentityProperties,
) : CommandHandler<GrantTenantNamespace, NamespaceGrant> {
    override fun handle(command: GrantTenantNamespace): NamespaceGrant {
        validate("authority", command.authority != identity.defaultRegistry, ValidationCode.CATALOG_NAMESPACE_INVALID) {
            NamespaceMessages.exchangeOnly(identity.defaultRegistry)
        }
        return recordGrant(jdbi, store, command.tenantKey, NamespaceName(command.authority, command.namespace))
    }
}

@Component
class SeedTenantNamespaceHandler(
    private val jdbi: Jdbi,
    private val store: NamespaceGrantStore,
) : CommandHandler<SeedTenantNamespace, NamespaceGrant> {
    override fun handle(command: SeedTenantNamespace): NamespaceGrant = recordGrant(jdbi, store, command.tenantKey, NamespaceName(command.authority, command.namespace))
}

/** Records [name] for the tenant by hand, or answers the grant it has; refuses one held under another authority. */
private fun recordGrant(jdbi: Jdbi, store: NamespaceGrantStore, tenantKey: TenantKey, name: NamespaceName): NamespaceGrant = jdbi.inTransaction<NamespaceGrant, Exception> { handle ->
    requireTenant(handle, tenantKey)
    val outcome = store.grant(handle, tenantKey, name, NamespaceGrantSource.ADMINISTRATOR, currentUserIdOrNull())
    if (outcome is GrantOutcome.TakenUnder) {
        throw ValidationException(
            "namespace",
            "This tenant holds the namespace ${name.namespace} under ${outcome.authority} already; one tenant holds a namespace under one authority.",
            ValidationCode.CATALOG_NAMESPACE_TAKEN,
        )
    }
    store.grants(handle, tenantKey).single { it.name == name }
}

@Component
class RevokeTenantNamespaceHandler(
    private val jdbi: Jdbi,
    private val store: NamespaceGrantStore,
) : CommandHandler<RevokeTenantNamespace, NamespaceRevocation> {
    override fun handle(command: RevokeTenantNamespace): NamespaceRevocation = jdbi.inTransaction<NamespaceRevocation, Exception> { handle ->
        requireTenant(handle, command.tenantKey)
        val name = NamespaceName(command.authority, command.namespace)
        val held = store.grants(handle, command.tenantKey).firstOrNull { it.name == name }
        validate("namespace", held?.removableByHand != false, ValidationCode.CATALOG_NAMESPACE_INVALID) {
            NamespaceMessages.connectionGrant(name)
        }
        val inUse = store.authoredCatalogsIn(handle, command.tenantKey, name)
        validate("namespace", inUse.isEmpty(), ValidationCode.CATALOG_NAMESPACE_IN_USE) {
            val catalogs = inUse.joinToString(", ") { it.value }
            "${name.namespace} is not removed: ${if (inUse.size == 1) "the catalog $catalogs is" else "the catalogs $catalogs are"} named in it. Move or delete ${if (inUse.size == 1) "it" else "them"} first."
        }
        NamespaceRevocation(name, store.revoke(handle, command.tenantKey, name))
    }
}

private fun requireTenant(handle: Handle, tenantKey: TenantKey) {
    val exists = handle.createQuery("SELECT EXISTS (SELECT 1 FROM tenants WHERE id = :tenantKey)")
        .bind("tenantKey", tenantKey).mapTo(Boolean::class.java).one()
    if (!exists) throw TenantNotFoundException(tenantKey)
}
