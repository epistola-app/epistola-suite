// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.exchange

import app.epistola.catalog.protocol.ReleaseInfo
import app.epistola.suite.catalog.CatalogArchiveBuilder
import app.epistola.suite.catalog.CatalogContentBuilder
import app.epistola.suite.catalog.CatalogFingerprintService
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.CatalogPublicationPolicy
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.common.UUIDv7
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.features.KnownFeatures
import app.epistola.suite.features.queries.ResolveAvailableFeatures
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Handle
import org.jdbi.v3.core.Jdbi
import org.springframework.stereotype.Component
import java.time.OffsetDateTime
import java.util.UUID

/** Explicitly queues the unchanged current release, or retries its failed Exchange attempt. */
data class PublishCurrentCatalogRelease(
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
) : Command<UUID>,
    RequiresPermission {
    override val permission = Permission.TEMPLATE_PUBLISH
}

@Component
class PublishCurrentCatalogReleaseHandler(
    private val jdbi: Jdbi,
    private val contentBuilder: CatalogContentBuilder,
    private val archiveBuilder: CatalogArchiveBuilder,
    private val fingerprintService: CatalogFingerprintService,
    private val properties: ExchangeProperties,
) : CommandHandler<PublishCurrentCatalogRelease, UUID> {
    override fun handle(command: PublishCurrentCatalogRelease): UUID {
        require(properties.enabled) { "Exchange publishing is disabled for this deployment" }
        require(ResolveAvailableFeatures(command.tenantKey).query()[KnownFeatures.CATALOG_PUBLISHING] == true) {
            "Exchange publishing is disabled for this tenant"
        }
        val catalog = requireNotNull(GetCatalog(command.tenantKey, command.catalogKey).query()) { "Catalog not found" }
        require(catalog.exchangePublicationPolicy != CatalogPublicationPolicy.NEVER) {
            "This catalog's publication policy forbids Exchange publishing"
        }
        val release = latestRelease(command)
        val previousAttempt = existingPublication(command, release.version)
        require(previousAttempt == null || previousAttempt.status == "FAILED") {
            "This release already has an Exchange publication attempt"
        }
        val archive = previousAttempt?.archive ?: run {
            val content = contentBuilder.build(command.tenantKey, command.catalogKey)
            fingerprintService.requirePublishable(content)
            require(fingerprintService.matchesFingerprint(content, release.fingerprint)) {
                "The working copy differs from v${release.version}; release those changes before publishing to Exchange"
            }
            archiveBuilder.build(
                content,
                ReleaseInfo(release.version, release.releasedAt.toString(), release.fingerprint),
            )
        }
        val publicationId = UUIDv7.generate()
        val idempotencyKey = UUIDv7.generate()
        return jdbi.inTransaction<UUID, Exception> { handle ->
            val namespace = resolveAndBindNamespace(handle, command, catalog.exchangeNamespacePreference)
            val existing = handle.createQuery(
                """
                SELECT id, status FROM catalog_release_publications
                WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey AND version = :version
                FOR UPDATE
                """,
            ).bind("tenantKey", command.tenantKey).bind("catalogKey", command.catalogKey)
                .bind("version", release.version).map { rs, _ ->
                    rs.getObject("id", UUID::class.java) to rs.getString("status")
                }.findOne().orElse(null)
            if (existing == null) {
                handle.createUpdate(
                    """
                    INSERT INTO catalog_release_publications
                        (id, tenant_key, catalog_key, version, fingerprint, namespace, archive, status, idempotency_key)
                    VALUES (:id, :tenantKey, :catalogKey, :version, :fingerprint, :namespace, :archive, :status, :key)
                    """,
                ).bind("id", publicationId).bind("tenantKey", command.tenantKey).bind("catalogKey", command.catalogKey)
                    .bind("version", release.version).bind("fingerprint", release.fingerprint).bind("namespace", namespace)
                    .bind("archive", archive).bind("status", if (namespace == null) "WAITING_SETUP" else "READY")
                    .bind("key", idempotencyKey).execute()
                publicationId
            } else {
                require(existing.second == "FAILED") { "This release already has an Exchange publication attempt" }
                handle.createUpdate(
                    """
                    UPDATE catalog_release_publications SET namespace = :namespace, archive = :archive,
                        status = :status, idempotency_key = :key, remote_publication_id = NULL,
                        remote_status_url = NULL, attempts = 0, next_attempt_at = NOW(), claimed_at = NULL,
                        last_error = NULL, updated_at = NOW()
                    WHERE id = :id
                    """,
                ).bind("namespace", namespace).bind("archive", archive)
                    .bind("status", if (namespace == null) "WAITING_SETUP" else "READY")
                    .bind("key", idempotencyKey).bind("id", existing.first).execute()
                existing.first
            }
        }
    }

    private fun latestRelease(command: PublishCurrentCatalogRelease): Release = jdbi.withHandle<Release, Exception> { handle ->
        handle.createQuery(
            """
            SELECT r.version, r.fingerprint, r.released_at
            FROM catalog_releases r
            JOIN catalogs c ON c.tenant_key = r.tenant_key AND c.id = r.catalog_key
            WHERE r.tenant_key = :tenantKey AND r.catalog_key = :catalogKey
              AND r.version = c.released_version
            """,
        ).bind("tenantKey", command.tenantKey).bind("catalogKey", command.catalogKey).map { rs, _ ->
            Release(
                rs.getString("version"),
                rs.getString("fingerprint"),
                rs.getObject("released_at", OffsetDateTime::class.java),
            )
        }.findOne().orElseThrow { IllegalArgumentException("Catalog has no release to publish") }
    }

    private fun existingPublication(command: PublishCurrentCatalogRelease, version: String): ExistingPublication? = jdbi.withHandle<ExistingPublication?, Exception> { handle ->
        handle.createQuery(
            """
                SELECT status, archive FROM catalog_release_publications
                WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey AND version = :version
                """,
        ).bind("tenantKey", command.tenantKey).bind("catalogKey", command.catalogKey).bind("version", version)
            .map { rs, _ -> ExistingPublication(rs.getString("status"), rs.getBytes("archive")) }
            .findOne().orElse(null)
    }

    private fun resolveAndBindNamespace(
        handle: Handle,
        command: PublishCurrentCatalogRelease,
        preference: String?,
    ): String? {
        val existing = handle.createQuery(
            "SELECT namespace FROM catalog_exchange_bindings WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey",
        ).bind("tenantKey", command.tenantKey).bind("catalogKey", command.catalogKey)
            .mapTo(String::class.java).findOne().orElse(null)
        if (existing != null) return existing
        val selected = handle.createQuery(
            """
            SELECT namespaces, default_namespace FROM exchange_tenant_connections
            WHERE tenant_key = :tenantKey AND status = 'ACTIVE'
            """,
        ).bind("tenantKey", command.tenantKey).map { rs, _ ->
            val allowed = (rs.getArray("namespaces").array as Array<*>).map(Any?::toString).toSet()
            (preference?.takeIf(allowed::contains) ?: rs.getString("default_namespace"))?.takeIf(allowed::contains)
        }.findOne().orElse(null) ?: return null
        handle.createUpdate(
            """
            INSERT INTO catalog_exchange_bindings (tenant_key, catalog_key, namespace)
            VALUES (:tenantKey, :catalogKey, :namespace)
            ON CONFLICT (tenant_key, catalog_key) DO NOTHING
            """,
        ).bind("tenantKey", command.tenantKey).bind("catalogKey", command.catalogKey).bind("namespace", selected).execute()
        return selected
    }

    private data class Release(val version: String, val fingerprint: String, val releasedAt: OffsetDateTime)

    private data class ExistingPublication(val status: String, val archive: ByteArray) {
        init {
            require(archive.isNotEmpty()) { "Failed Exchange publication has no retained archive" }
        }
    }
}
