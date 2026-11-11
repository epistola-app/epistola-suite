// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.support.connection

import app.epistola.suite.installation.InstallationProperties
import app.epistola.suite.time.EpistolaClock
import com.nimbusds.jose.jwk.ECKey
import org.slf4j.Logger
import java.net.URI
import java.time.Instant

// What DefaultSupportConnection keeps per node, and the registration it sends.

/**
 * A token this node holds. [requested] is what it asked for; [scopes] what it got of that.
 * About a scope it did not ask for, it says nothing.
 */
internal class SupportCachedToken(
    val clientId: String,
    val value: String,
    val expiresAt: Instant,
    val scopes: Set<SupportScope>,
    val obtainedAt: Instant,
    val requested: Set<SupportScope>,
    /**
     * The [SupportConnectionStore.version] read just before the token was asked for: a refusal
     * stored at this version or before was stored before the request, so this token's scopes are
     * newer news than it; a later one may be about a withdrawal this token predates. Orders
     * tokens and refusals across nodes without comparing instants, which tie and skew.
     */
    val seq: Long,
)

/** This node's last failed call to the support service. */
internal class SupportNodeFailure(val at: Instant, val message: String)

/** Not before [until]: this node's [failures]-th failed call in a row backs off until then. */
internal class SupportBackoff(val failures: Int, val until: Instant)

internal class SupportCachedMetadata(val metadata: SupportServiceMetadata, val fetchedAt: Instant)

/**
 * Who Epistola contacts about this installation. [confirmed]: an installation administrator checked
 * it before registering; otherwise it comes from `epistola.installation.*`.
 */
data class SupportContact(val companyName: String, val adminEmail: String, val confirmed: Boolean)

internal fun supportContact(stored: StoredSupportContact?, installation: InstallationProperties): SupportContact = stored?.let { SupportContact(it.companyName, it.adminEmail, confirmed = true) }
    ?: SupportContact(installation.companyName.trim(), installation.adminEmail.trim(), confirmed = false)

/** The RFC 7591 registration request for [key], with the installation's own fields (`docs/support-protocol.md`). */
internal fun supportRegistrationRequest(key: ECKey, installationId: String, installation: InstallationProperties, contact: SupportContact, nodeId: String, version: String?): Map<String, Any?> = mapOf(
    "client_name" to clientName(installation).take(MAX_FIELD),
    "grant_types" to listOf("client_credentials"),
    "token_endpoint_auth_method" to "private_key_jwt",
    "jwks" to mapOf("keys" to listOf(SupportClientKeys.publicJwk(key))),
    "software_id" to "epistola-suite",
    "software_version" to (version ?: "dev"),
    "epistola_installation_id" to installationId,
    "epistola_environment" to installation.environment.field(),
    "epistola_company_name" to contact.companyName.field(),
    "epistola_admin_email" to contact.adminEmail.field(),
    "epistola_node_id" to nodeId.field(),
)

private fun clientName(installation: InstallationProperties): String = installation.name.trim().ifBlank {
    listOf(installation.companyName, installation.environment).map(String::trim).filter(String::isNotEmpty).joinToString(" — ")
}.ifBlank { "Epistola Suite" }

private fun String.field(): String? = trim().takeIf(String::isNotEmpty)?.take(MAX_FIELD)

private const val MAX_FIELD = 200

/**
 * Reads the stored rows for [DefaultSupportConnection]'s snapshot. A row that cannot be read never
 * grants more than was chosen: the selection last read on this node ([previousServices]) stands, and
 * every scope counts as refused; either marks the result partial.
 */
internal fun readSupportRows(store: SupportConnectionStore, issuer: URI?, previousServices: StoredSupportServices?, version: Long?, log: Logger): SupportRowsSnapshot {
    var partial = false
    val read = SupportRowsSnapshot(
        credentials = runCatching { store.credentials() }.onFailure { log.warn("Support connection: cannot read the stored registration reason=\"{}\"", it.message) }.getOrNull(),
        status = runCatching { store.status() }.getOrNull(),
        issuer = issuer,
        // Unreadable: the selection last read on this node stands, or none — never more than was chosen.
        services = runCatching { store.services() }.onFailure { log.warn("Support connection: cannot read the selected services reason=\"{}\"", it.message) }
            .getOrElse {
                partial = true
                previousServices ?: StoredSupportServices()
            },
        refusals = runCatching { store.refusals() }.onFailure { log.warn("Support connection: cannot read the refused scopes reason=\"{}\"", it.message) }
            .map { stored -> stored.refusals.mapNotNull { (name, seq) -> SupportScope.parse(name)?.let { it to seq } }.toMap() }
            // Unreadable: every scope counts as refused, and no token settles it, until they can be
            // read again — never more than was granted. The refusals read before may lack the one
            // that raised the version (and none are kept after invalidate()).
            .getOrElse {
                partial = true
                SupportScope.entries.associateWith { Long.MAX_VALUE }
            },
        // Unreadable: no warning shown; it closes no gate either way.
        nodeClocks = runCatching { store.nodeClockErrors() }.getOrDefault(emptyMap()),
        version = version,
        readAtNanos = System.nanoTime(),
        versionCheckedAt = EpistolaClock.instant(),
        partial = partial,
    )
    return read
}
