// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.commands

import app.epistola.catalog.protocol.ReleaseInfo
import app.epistola.suite.catalog.Catalog
import app.epistola.suite.catalog.CatalogArchiveBuilder
import app.epistola.suite.catalog.CatalogContentBuilder
import app.epistola.suite.catalog.CatalogFingerprintService
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.CatalogNotFoundException
import app.epistola.suite.catalog.CatalogReadOnlyException
import app.epistola.suite.catalog.CatalogType
import app.epistola.suite.catalog.SemVer
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.common.UUIDv7
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.config.bindJsonb
import app.epistola.suite.exchange.ExchangeProperties
import app.epistola.suite.features.KnownFeatures
import app.epistola.suite.features.queries.ResolveAvailableFeatures
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.tenants.queries.GetTenant
import org.jdbi.v3.core.Jdbi
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Cuts a new release of an AUTHORED catalog: records an explicit, immutable
 * release boundary (author-set SemVer + content fingerprint + notes + a
 * manifest snapshot) and advances the catalog's released-version pointer.
 *
 * See [`docs/catalog-versioning.md`](../../../../../../../../../docs/catalog-versioning.md).
 */
data class ReleaseCatalogVersion(
    override val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
    val version: String,
    val notes: String? = null,
    val exchangePublication: ReleaseExchangePublication = ReleaseExchangePublication.DEFAULT,
) : Command<ReleaseCatalogVersionResult>,
    RequiresPermission {
    override val permission get() = Permission.TEMPLATE_PUBLISH
}

enum class ReleaseExchangePublication { DEFAULT, PUBLISH, SKIP, SUPPRESS }

data class ReleaseCatalogVersionResult(
    val version: String,
    val fingerprint: String,
    val previousVersion: String?,
    val releasedAt: OffsetDateTime,
    /** True when the released content is byte-identical to a prior release. */
    val unchangedContent: Boolean,
    val exchangePublicationId: UUID? = null,
)

/** Thrown when the requested release version is not a strictly increasing SemVer. */
class CatalogReleaseVersionException(message: String) : RuntimeException(message)

@Component
class ReleaseCatalogVersionHandler(
    private val jdbi: Jdbi,
    private val contentBuilder: CatalogContentBuilder,
    private val archiveBuilder: CatalogArchiveBuilder,
    private val fingerprintService: CatalogFingerprintService,
    private val objectMapper: ObjectMapper,
    private val exchangeProperties: ExchangeProperties,
) : CommandHandler<ReleaseCatalogVersion, ReleaseCatalogVersionResult> {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun handle(command: ReleaseCatalogVersion): ReleaseCatalogVersionResult {
        val catalog = GetCatalog(command.tenantKey, command.catalogKey).query()
            ?: throw CatalogNotFoundException(command.catalogKey)
        if (catalog.type != CatalogType.AUTHORED) {
            throw CatalogReadOnlyException(command.catalogKey)
        }

        val newVersion = try {
            SemVer.parse(command.version)
        } catch (e: IllegalArgumentException) {
            throw CatalogReleaseVersionException(e.message ?: "Invalid version '${command.version}'")
        }

        val existing = loadReleases(command.tenantKey, command.catalogKey)
        val latest = existing.mapNotNull { SemVer.parseOrNull(it.version) }.maxOrNull()
        if (latest != null && newVersion <= latest) {
            throw CatalogReleaseVersionException(
                "Version $newVersion must be greater than the last release $latest",
            )
        }

        val content = contentBuilder.build(command.tenantKey, command.catalogKey)
        fingerprintService.requirePublishable(content)
        val fingerprint = fingerprintService.fingerprint(content)
        val unchanged = existing.any { fingerprintService.matchesFingerprint(content, it.fingerprint) }
        if (unchanged) {
            logger.warn(
                "Releasing catalog '{}' v{} with content identical to a previous release (fingerprint {})",
                command.catalogKey.value,
                newVersion,
                fingerprint,
            )
        }

        // released_at MUST come from the database clock — the same clock that
        // stamps resource updated_at / imported_at — so the AUTHORED
        // working-copy drift comparison (max(resource.updated_at) >
        // GREATEST(released_at, imported_at)) is exact. A JVM
        // OffsetDateTime.now() here drifts vs the DB clock and can make a
        // freshly released, unedited catalog look "pending".
        val releasedAt = jdbi.withHandle<OffsetDateTime, Exception> { handle ->
            handle.createQuery("SELECT NOW()").mapTo(OffsetDateTime::class.java).one()
        }
        val releaseInfo = ReleaseInfo(version = newVersion.toString(), releasedAt = releasedAt.toString(), fingerprint = fingerprint)
        val manifest = content.toManifest(releaseInfo)
        val publicationId = shouldPublish(command, catalog).takeIf { it }?.let { UUIDv7.generate() }
        val archive = publicationId?.let { archiveBuilder.build(content, releaseInfo) }

        jdbi.useTransaction<Exception> { handle ->
            handle.createUpdate(
                """
                INSERT INTO catalog_releases (tenant_key, catalog_key, version, fingerprint, notes, manifest_snapshot, released_at)
                VALUES (:t, :c, :version, :fingerprint, :notes, CAST(:snapshot AS JSONB), :releasedAt)
                """,
            )
                .bind("t", command.tenantKey)
                .bind("c", command.catalogKey)
                .bind("version", newVersion.toString())
                .bind("fingerprint", fingerprint)
                .bind("notes", command.notes)
                .bindJsonb("snapshot", manifest, objectMapper)
                .bind("releasedAt", releasedAt)
                .execute()

            handle.createUpdate(
                """
                UPDATE catalogs
                SET released_version = :version, released_fingerprint = :fingerprint,
                    released_at = :releasedAt, updated_at = NOW()
                WHERE tenant_key = :t AND id = :c
                """,
            )
                .bind("t", command.tenantKey)
                .bind("c", command.catalogKey)
                .bind("version", newVersion.toString())
                .bind("fingerprint", fingerprint)
                .bind("releasedAt", releasedAt)
                .execute()

            if (publicationId != null && archive != null) {
                val namespace = resolveAndBindNamespace(handle, command, catalog.exchangeNamespacePreference)
                handle.createUpdate(
                    """
                    INSERT INTO catalog_release_publications
                        (id, tenant_key, catalog_key, version, fingerprint, namespace, archive,
                         status, idempotency_key)
                    VALUES (:id, :tenantKey, :catalogKey, :version, :fingerprint, :namespace, :archive,
                            :status, :idempotencyKey)
                    """,
                )
                    .bind("id", publicationId)
                    .bind("tenantKey", command.tenantKey)
                    .bind("catalogKey", command.catalogKey)
                    .bind("version", newVersion.toString())
                    .bind("fingerprint", fingerprint)
                    .bind("namespace", namespace)
                    .bind("archive", archive)
                    .bind("status", if (namespace == null) "WAITING_SETUP" else "READY")
                    .bind("idempotencyKey", UUIDv7.generate())
                    .execute()
            }
        }

        logger.info(
            "Released catalog '{}': {} -> {} (fingerprint {})",
            command.catalogKey.value,
            latest?.toString() ?: "—",
            newVersion,
            fingerprint,
        )

        return ReleaseCatalogVersionResult(
            version = newVersion.toString(),
            fingerprint = fingerprint,
            previousVersion = latest?.toString(),
            releasedAt = releasedAt,
            unchangedContent = unchanged,
            exchangePublicationId = publicationId,
        )
    }

    private fun shouldPublish(command: ReleaseCatalogVersion, catalog: Catalog): Boolean {
        if (!exchangeProperties.enabled || command.exchangePublication == ReleaseExchangePublication.SUPPRESS) return false
        if (ResolveAvailableFeatures(command.tenantKey).query()[KnownFeatures.CATALOG_PUBLISHING] != true) return false
        val tenantDefault = requireNotNull(GetTenant(command.tenantKey).query()).publishCatalogsByDefault
        val override = when (command.exchangePublication) {
            ReleaseExchangePublication.DEFAULT -> null
            ReleaseExchangePublication.PUBLISH -> true
            ReleaseExchangePublication.SKIP -> false
            ReleaseExchangePublication.SUPPRESS -> return false
        }
        return catalog.exchangePublicationPolicy.resolve(tenantDefault, override)
    }

    private fun resolveAndBindNamespace(
        handle: org.jdbi.v3.core.Handle,
        command: ReleaseCatalogVersion,
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
        ).bind("tenantKey", command.tenantKey).bind("catalogKey", command.catalogKey)
            .bind("namespace", selected).execute()
        return selected
    }

    private data class ReleaseRow(val version: String, val fingerprint: String)

    private fun loadReleases(tenantKey: TenantKey, catalogKey: CatalogKey): List<ReleaseRow> = jdbi.withHandle<List<ReleaseRow>, Exception> { handle ->
        handle.createQuery(
            "SELECT version, fingerprint FROM catalog_releases WHERE tenant_key = :t AND catalog_key = :c",
        )
            .bind("t", tenantKey)
            .bind("c", catalogKey)
            .map { rs, _ -> ReleaseRow(rs.getString("version"), rs.getString("fingerprint")) }
            .list()
    }
}
