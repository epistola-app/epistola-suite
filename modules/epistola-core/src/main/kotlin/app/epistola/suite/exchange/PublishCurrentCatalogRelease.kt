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
import app.epistola.suite.catalog.revisions.ReleaseContentAssembler
import app.epistola.suite.catalog.revisions.ReleaseEntryStore
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
    /**
     * Which release to send, or null for the one the catalog is currently on.
     *
     * Any release may be published, not only the latest. That was never a decision — while the
     * archive had to be rebuilt from the working copy, only the current release could possibly
     * match it. A release carries its own content now, so an older one is as publishable as a new
     * one, and a catalog whose author has moved on can still send the version people are asking
     * for.
     *
     * Deliberately no ordering rule here: sending 1.0.0 after 1.1.0 is allowed. What a namespace
     * accepts is Exchange's to decide and Exchange's to enforce — a second opinion in the Suite
     * would only be wrong in a different way the first time the two disagreed.
     */
    val version: String? = null,
) : Command<UUID>,
    RequiresPermission {
    override val permission = Permission.CATALOG_PUBLISH
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
    private val assembler: ReleaseContentAssembler,
    private val releaseEntryStore: ReleaseEntryStore,
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

        // Nothing is queued without a destination, so this is checked before any work is done.
        validate(
            "namespace",
            jdbi.withHandle<Boolean, Exception> { handle ->
                namespaceBinder.existingBinding(handle, command.tenantKey, command.catalogKey) != null
            },
            ValidationCode.EXCHANGE_NAMESPACE_UNAVAILABLE,
        ) { "Choose the Exchange namespace for this catalog before publishing it." }

        val release = releaseToPublish(command)
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
        val resumable = setOf(CatalogPublicationStatus.FAILED, CatalogPublicationStatus.CANCELLED)
        validate(
            "publication",
            previousStatus == null || previousStatus in resumable,
            ValidationCode.PUBLICATION_ALREADY_QUEUED,
        ) { "This release already has an Exchange publication attempt." }

        // A failed attempt kept its archive and must resubmit exactly those bytes. A first attempt —
        // or one that was cancelled, which released its archive — rebuilds from the working copy,
        // and is refused if that has drifted from the release.
        val archive = if (previousStatus == CatalogPublicationStatus.FAILED) null else buildReleaseArchive(command, release)

        return jdbi.inTransaction<UUID, Exception> { handle ->
            val namespace = requireNotNull(namespaceBinder.existingBinding(handle, command.tenantKey, command.catalogKey))
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
                        existing.status in resumable,
                        ValidationCode.PUBLICATION_ALREADY_QUEUED,
                    ) { "This release already has an Exchange publication attempt." }
                    validate(
                        "publication",
                        existing.status == CatalogPublicationStatus.CANCELLED || existing.archiveRetained,
                        ValidationCode.PUBLICATION_ARCHIVE_MISSING,
                    ) { "The failed publication no longer has its released archive; release a new version instead." }
                    store.requeue(handle, existing.id, namespace, UUIDv7.generate(), archive)
                    existing.id
                }
            }
        }
    }

    /**
     * The bytes of [release], preferring the content the release retained.
     *
     * A release that kept its content needs no working copy at all, which is the point: publishing
     * v1.0.0 is publishing what v1.0.0 was, whatever has been edited since. The fingerprint is
     * recomputed and a mismatch refuses, the same guard the release export applies — it should be
     * impossible, and submitting an archive whose contents contradict the fingerprint it advertises
     * would spread that rather than stop it.
     *
     * A release cut before content was retained has only the working copy to rebuild from, so it
     * keeps the old refusal: those bytes are only that release's bytes while nothing has changed.
     */
    private fun buildReleaseArchive(command: PublishCurrentCatalogRelease, release: Release): ByteArray {
        val retained = assembler.assemble(command.tenantKey, command.catalogKey, release.version)
        if (retained != null) {
            val rebuilt = fingerprintService.fingerprint(retained.content)
            check(rebuilt == release.fingerprint) {
                "Release ${release.version} of catalog '${command.catalogKey.value}' rebuilds to fingerprint " +
                    "$rebuilt but was released as ${release.fingerprint}; its retained content has been altered."
            }
            return archiveBuilder.build(retained.content, retained.release)
        }

        val content = contentBuilder.build(command.tenantKey, command.catalogKey)
        fingerprintService.requirePublishable(content)
        validate(
            "publication",
            fingerprintService.matchesFingerprint(content, release.fingerprint),
            ValidationCode.PUBLICATION_WORKING_COPY_DRIFTED,
        ) { "The working copy differs from v${release.version}, and that release did not retain its content; release those changes before publishing to Exchange." }
        return archiveBuilder.build(
            content,
            ReleaseInfo(release.version, release.releasedAt.toString(), release.fingerprint),
        )
    }

    /**
     * The release to send: the one named, or the one the catalog is currently on.
     *
     * A named release that kept no content is refused rather than rebuilt. Only the current release
     * can be rebuilt from the working copy, and only while nothing has changed — reaching for an
     * older one would quietly send today's content under yesterday's version.
     */
    private fun releaseToPublish(command: PublishCurrentCatalogRelease): Release {
        val release = findRelease(command) ?: throw ValidationException(
            "catalogKey",
            command.version?.let { "This catalog has no release $it to publish." } ?: "This catalog has no release to publish.",
            ValidationCode.PUBLICATION_NO_RELEASE,
        )
        if (command.version != null) {
            validate(
                "publication",
                jdbi.withHandle<Boolean, Exception> { handle ->
                    releaseEntryStore.retainedVersions(handle, command.tenantKey, command.catalogKey).contains(release.version)
                },
                ValidationCode.PUBLICATION_WORKING_COPY_DRIFTED,
            ) { "Release ${release.version} did not retain its content and can no longer be rebuilt, so only the catalog's current release can be published." }
        }
        return release
    }

    private fun findRelease(command: PublishCurrentCatalogRelease): Release? = jdbi.withHandle<Release?, Exception> { handle ->
        val selection = if (command.version != null) {
            "AND r.version = :version"
        } else {
            "AND r.version = c.released_version"
        }
        handle.createQuery(
            """
            SELECT r.version, r.fingerprint, r.released_at
            FROM catalog_releases r
            JOIN catalogs c ON c.tenant_key = r.tenant_key AND c.id = r.catalog_key
            WHERE r.tenant_key = :tenantKey AND r.catalog_key = :catalogKey
              $selection
            """,
        ).apply { if (command.version != null) bind("version", command.version) }
            .bind("tenantKey", command.tenantKey).bind("catalogKey", command.catalogKey).map { rs, _ ->
                Release(
                    rs.getString("version"),
                    rs.getString("fingerprint"),
                    rs.getObject("released_at", OffsetDateTime::class.java),
                )
            }.findOne().orElse(null)
    }

    private data class Release(val version: String, val fingerprint: String, val releasedAt: OffsetDateTime)
}
