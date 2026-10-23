// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.namespaces

import app.epistola.catalog.protocol.CatalogNamespaces
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.PlatformRole
import app.epistola.suite.security.RequiresPlatformRole
import app.epistola.suite.security.currentUserIdOrNull
import app.epistola.suite.tenants.TenantNotFoundException
import app.epistola.suite.validation.ValidationCode
import app.epistola.suite.validation.ValidationException
import app.epistola.suite.validation.validate
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component

/**
 * A platform administrator grants a tenant a namespace under an authority they name, usually the
 * organisation's own domain (ADR 0034, section 3). Several tenants may hold one namespace, and a
 * tenant several. Granting one the tenant holds already changes nothing; one it holds under another
 * authority is refused, so a namespace token names one namespace in a tenant (ADR 0034, section 2).
 * `local` needs no grant and is refused here.
 */
data class GrantTenantNamespace(
    val tenantKey: TenantKey,
    val authority: String,
    val namespace: String,
) : Command<NamespaceGrant>,
    RequiresPlatformRole {
    override val platformRole = PlatformRole.TENANT_MANAGER

    init {
        validateNamespaceName(authority, namespace)
        validate("authority", !LocalAuthority.isLocal(authority), ValidationCode.CATALOG_NAMESPACE_INVALID) {
            "Every tenant may name catalogs under local without a grant; grant a namespace under a domain."
        }
    }
}

/**
 * A platform administrator revokes a namespace from a tenant. Refused while catalogs the tenant
 * authors are named in it, which are named: they would be left unable to release, so they are moved
 * or deleted first. Catalogs it received or that Epistola supplies do not count: receiving needs no
 * grant.
 */
data class RevokeTenantNamespace(
    val tenantKey: TenantKey,
    val authority: String,
    val namespace: String,
) : Command<NamespaceRevocation>,
    RequiresPlatformRole {
    override val platformRole = PlatformRole.TENANT_MANAGER
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
) : CommandHandler<GrantTenantNamespace, NamespaceGrant> {
    override fun handle(command: GrantTenantNamespace): NamespaceGrant = jdbi.inTransaction<NamespaceGrant, Exception> { handle ->
        requireTenant(handle, command.tenantKey)
        val name = NamespaceName(command.authority, command.namespace)
        val outcome = store.grant(handle, command.tenantKey, name, NamespaceGrantSource.ADMINISTRATOR, currentUserIdOrNull())
        if (outcome is GrantOutcome.TakenUnder) {
            throw ValidationException(
                "namespace",
                "This tenant holds the namespace ${name.namespace} under ${outcome.authority} already; one tenant holds a namespace under one authority.",
                ValidationCode.CATALOG_NAMESPACE_TAKEN,
            )
        }
        store.grants(handle, command.tenantKey).single { it.name == name }
    }
}

@Component
class RevokeTenantNamespaceHandler(
    private val jdbi: Jdbi,
    private val store: NamespaceGrantStore,
) : CommandHandler<RevokeTenantNamespace, NamespaceRevocation> {
    override fun handle(command: RevokeTenantNamespace): NamespaceRevocation = jdbi.inTransaction<NamespaceRevocation, Exception> { handle ->
        requireTenant(handle, command.tenantKey)
        val name = NamespaceName(command.authority, command.namespace)
        val inUse = store.authoredCatalogsIn(handle, command.tenantKey, name)
        validate("namespace", inUse.isEmpty(), ValidationCode.CATALOG_NAMESPACE_IN_USE) {
            val catalogs = inUse.joinToString(", ") { it.value }
            "${name.namespace} is not revoked: ${if (inUse.size == 1) "the catalog $catalogs is" else "the catalogs $catalogs are"} named in it. Move or delete ${if (inUse.size == 1) "it" else "them"} first."
        }
        NamespaceRevocation(name, store.revoke(handle, command.tenantKey, name))
    }
}

private fun requireTenant(handle: Handle, tenantKey: TenantKey) {
    val exists = handle.createQuery("SELECT EXISTS (SELECT 1 FROM tenants WHERE id = :tenantKey)")
        .bind("tenantKey", tenantKey).mapTo(Boolean::class.java).one()
    if (!exists) throw TenantNotFoundException(tenantKey)
}
