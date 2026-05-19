package app.epistola.suite.catalog.commands

import app.epistola.catalog.protocol.ReleaseInfo
import app.epistola.suite.catalog.CatalogContentBuilder
import app.epistola.suite.catalog.CatalogFingerprintService
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.CatalogType
import app.epistola.suite.catalog.SemVer
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.config.bindJsonb
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.time.OffsetDateTime

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
) : Command<ReleaseCatalogVersionResult>,
    RequiresPermission {
    override val permission get() = Permission.TEMPLATE_PUBLISH
}

data class ReleaseCatalogVersionResult(
    val version: String,
    val fingerprint: String,
    val previousVersion: String?,
    val releasedAt: OffsetDateTime,
    /** True when the released content is byte-identical to a prior release. */
    val unchangedContent: Boolean,
)

/** Thrown when the requested release version is not a strictly increasing SemVer. */
class CatalogReleaseVersionException(message: String) : RuntimeException(message)

@Component
class ReleaseCatalogVersionHandler(
    private val jdbi: Jdbi,
    private val contentBuilder: CatalogContentBuilder,
    private val fingerprintService: CatalogFingerprintService,
    private val objectMapper: ObjectMapper,
) : CommandHandler<ReleaseCatalogVersion, ReleaseCatalogVersionResult> {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun handle(command: ReleaseCatalogVersion): ReleaseCatalogVersionResult {
        val catalog = GetCatalog(command.tenantKey, command.catalogKey).query()
            ?: throw IllegalArgumentException("Catalog not found: ${command.catalogKey}")
        check(catalog.type == CatalogType.AUTHORED) {
            "Only AUTHORED catalogs can be released — '${command.catalogKey.value}' is ${catalog.type}"
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
        val fingerprint = fingerprintService.fingerprint(content)
        val unchanged = existing.any { it.fingerprint == fingerprint }
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
        val manifest = content.toManifest(
            ReleaseInfo(version = newVersion.toString(), releasedAt = releasedAt.toString(), fingerprint = fingerprint),
        )

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
        )
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
