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
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.query
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import app.epistola.suite.validation.ValidationCode
import app.epistola.suite.validation.ValidationException
import app.epistola.suite.validation.validate
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

/**
 * The out-of-band path into the outbox, for the two cases a release does not cover: a release that
 * was cut without publishing, and a publication Exchange failed operationally.
 *
 * A retry deliberately resubmits the **retained** archive rather than rebuilding it — the working
 * copy may have moved on since, and an immutable version must always mean the same bytes.
 */
@Component
class PublishCurrentCatalogReleaseHandler(
    private val jdbi: Jdbi,
    private val contentBuilder: CatalogContentBuilder,
    private val archiveBuilder: CatalogArchiveBuilder,
    private val fingerprintService: CatalogFingerprintService,
    private val availability: ExchangeAvailability,
    private val namespaceBinder: ExchangeNamespaceBinder,
    private val store: CatalogPublicationStore,
) : CommandHandler<PublishCurrentCatalogRelease, UUID> {

    override fun handle(command: PublishCurrentCatalogRelease): UUID {
        validate("publication", availability.isAvailable(command.tenantKey), ValidationCode.PUBLICATION_UNAVAILABLE) {
            "Exchange publishing is not enabled for this deployment and tenant."
        }
        val catalog = GetCatalog(command.tenantKey, command.catalogKey).query()
        validate("catalogKey", catalog != null, ValidationCode.PUBLICATION_UNAVAILABLE) { "Catalog not found." }
        validate(
            "publicationPolicy",
            catalog!!.exchangePublicationPolicy != CatalogPublicationPolicy.NEVER,
            ValidationCode.PUBLICATION_FORBIDDEN_BY_POLICY,
        ) { "This catalog's publication policy forbids Exchange publishing." }

        val release = latestRelease(command)
        // Read the status without the archive: a 10 MB blob must not be fetched just to branch.
        val previousStatus = jdbi.withHandle<CatalogPublicationStatus?, Exception> { handle ->
            handle.createQuery(
                """
                SELECT status FROM catalog_release_publications
                WHERE tenant_key = :tenantKey AND catalog_key = :catalogKey AND version = :version
                """,
            ).bind("tenantKey", command.tenantKey).bind("catalogKey", command.catalogKey)
                .bind("version", release.version)
                .mapTo(CatalogPublicationStatus::class.java).findOne().orElse(null)
        }
        validate(
            "publication",
            previousStatus == null || previousStatus == CatalogPublicationStatus.FAILED,
            ValidationCode.PUBLICATION_ALREADY_QUEUED,
        ) { "This release already has an Exchange publication attempt." }

        // A fresh queue rebuilds the archive from the working copy and refuses if it has drifted;
        // a retry must reuse the bytes that were already released.
        val archive = if (previousStatus == null) buildReleaseArchive(command, release) else null

        return jdbi.inTransaction<UUID, Exception> { handle ->
            val namespace = namespaceBinder.resolveAndBind(handle, command.tenantKey, command.catalogKey)
            val existing = store.findByVersion(handle, command.tenantKey, command.catalogKey, release.version)
            when {
                existing == null -> UUIDv7.generate().also { id ->
                    store.insert(
                        handle = handle,
                        id = id,
                        tenantKey = command.tenantKey,
                        catalogKey = command.catalogKey,
                        version = release.version,
                        fingerprint = release.fingerprint,
                        namespace = namespace,
                        archive = requireNotNull(archive),
                        idempotencyKey = UUIDv7.generate(),
                    )
                }

                else -> {
                    validate(
                        "publication",
                        existing.status == CatalogPublicationStatus.FAILED,
                        ValidationCode.PUBLICATION_ALREADY_QUEUED,
                    ) { "This release already has an Exchange publication attempt." }
                    validate("publication", existing.archiveRetained, ValidationCode.PUBLICATION_ARCHIVE_MISSING) {
                        "The failed publication no longer has its released archive; release a new version instead."
                    }
                    store.requeue(handle, existing.id, namespace, UUIDv7.generate())
                    existing.id
                }
            }
        }
    }

    private fun buildReleaseArchive(command: PublishCurrentCatalogRelease, release: Release): ByteArray {
        val content = contentBuilder.build(command.tenantKey, command.catalogKey)
        fingerprintService.requirePublishable(content)
        validate(
            "publication",
            fingerprintService.matchesFingerprint(content, release.fingerprint),
            ValidationCode.PUBLICATION_WORKING_COPY_DRIFTED,
        ) { "The working copy differs from v${release.version}; release those changes before publishing to Exchange." }
        return archiveBuilder.build(
            content,
            ReleaseInfo(release.version, release.releasedAt.toString(), release.fingerprint),
        )
    }

    private fun latestRelease(command: PublishCurrentCatalogRelease): Release = jdbi.withHandle<Release?, Exception> { handle ->
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
        }.findOne().orElse(null)
    } ?: throw ValidationException(
        "catalogKey",
        "This catalog has no release to publish.",
        ValidationCode.PUBLICATION_NO_RELEASE,
    )

    private data class Release(val version: String, val fingerprint: String, val releasedAt: OffsetDateTime)
}
