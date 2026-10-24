// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.exchange

import app.epistola.catalog.protocol.ReleaseInfo
import app.epistola.suite.catalog.CatalogArchiveBuilder
import app.epistola.suite.catalog.CatalogFingerprintService
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.CatalogPublicationPolicy
import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.catalogAt
import app.epistola.suite.catalog.catalogName
import app.epistola.suite.catalog.namespaces.LocalAuthority
import app.epistola.suite.catalog.namespaces.NamespaceMessages
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.catalog.revisions.ReleaseContentAssembler
import app.epistola.suite.common.UUIDv7
import app.epistola.suite.common.ids.CatalogId
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

/**
 * Explicitly queues the unchanged current release, or retries its failed Exchange attempt.
 *
 * Idempotent, as the contract's `publishReleaseToRegistry` is for the Exchange (ADR 0008): a release
 * already queued answers [ExchangePublicationOutcome.Status.ALREADY_QUEUED] and is not queued again,
 * one the Exchange accepted [ExchangePublicationOutcome.Status.ALREADY_PUBLISHED], so a retry gets the
 * same answer. One the Exchange rejected is refused: that number cannot be published again.
 */
data class PublishCurrentCatalogRelease(
    val catalog: CatalogId,
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
) : Command<ExchangePublicationOutcome>,
    RequiresPermission {
    override val tenantKey: TenantKey get() = catalog.tenantKey
    val catalogKey: CatalogKey get() = catalog.key
    override val permission = Permission.CATALOG_PUBLISH
}

/** What publishing a release to the Exchange did: its publication, and whether this call queued it. */
data class ExchangePublicationOutcome(
    val publicationId: UUID,
    val version: String,
    val status: Status,
) {
    enum class Status {
        /** Queued by this call: a first attempt, or a failed or cancelled one queued again. */
        QUEUED,

        /** It was queued (or being sent) already; nothing was queued again. */
        ALREADY_QUEUED,

        /** The Exchange accepted it already; nothing was queued. */
        ALREADY_PUBLISHED,
    }
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
    private val archiveBuilder: CatalogArchiveBuilder,
    private val fingerprintService: CatalogFingerprintService,
    private val assembler: ReleaseContentAssembler,
    private val availability: ExchangeAvailability,
    private val namespaceBinder: ExchangeNamespaceBinder,
    private val store: CatalogPublicationStore,
    private val identity: app.epistola.suite.catalog.revisions.ReleaseIdentity,
) : CommandHandler<PublishCurrentCatalogRelease, ExchangePublicationOutcome> {

    override fun handle(command: PublishCurrentCatalogRelease): ExchangePublicationOutcome {
        validate("publication", availability.isAvailable(command.tenantKey), ValidationCode.PUBLICATION_UNAVAILABLE) {
            "Exchange publishing is not enabled for this deployment and tenant."
        }
        val catalog = GetCatalog(command.catalog).query()
        validate("catalogKey", catalog != null, ValidationCode.PUBLICATION_UNAVAILABLE) { "Catalog not found." }
        validate(
            "publicationPolicy",
            catalog!!.exchangePublicationPolicy != CatalogPublicationPolicy.NEVER,
            ValidationCode.PUBLICATION_FORBIDDEN_BY_POLICY,
        ) { "This catalog's publication policy forbids Exchange publishing." }

        // A catalog named under local never leaves the organisation (ADR 0034, section 7), whatever
        // namespace the connection grants.
        validate("namespace", !LocalAuthority.isLocal(catalog.registry), ValidationCode.CATALOG_NAMESPACE_LOCAL) {
            NamespaceMessages.notPublished(catalog.catalogName.toString())
        }

        // Nothing is queued without a destination the connection grants, so this is checked first.
        validate(
            "namespace",
            jdbi.withHandle<Boolean, Exception> { handle ->
                namespaceBinder.namespaceOf(handle, command.catalog) in namespaceBinder.grantedNamespaces(handle, command.tenantKey)
            },
            ValidationCode.EXCHANGE_NAMESPACE_UNAVAILABLE,
        ) { "This catalog's namespace is not one the Exchange connection grants. Choose a granted one in its settings." }

        val release = releaseToPublish(command)
        // Read the status without the archive: a 10 MB blob must not be fetched just to branch.
        val previous = jdbi.withHandle<Pair<UUID, CatalogPublicationStatus>?, Exception> { handle ->
            handle.createQuery(
                """
                SELECT id, status FROM catalog_release_publications
                WHERE tenant_key = :tenantKey AND catalog_id = ${catalogAt("tenantKey", "catalogKey")} AND version = :version
                """,
            ).bind("tenantKey", command.tenantKey).bindCatalog("catalogKey", command.catalog)
                .bind("version", release.version)
                .map { rs, _ -> rs.getObject("id", UUID::class.java) to CatalogPublicationStatus.valueOf(rs.getString("status")) }
                .findOne().orElse(null)
        }
        val previousStatus = previous?.second
        previous?.let { (id, status) -> repeated(id, release.version, status)?.let { return it } }

        // A failed attempt kept its archive and must resubmit exactly those bytes. A first attempt —
        // or one that was cancelled, which released its archive — rebuilds from the working copy,
        // and is refused if that has drifted from the release.
        val archive = if (previousStatus == CatalogPublicationStatus.FAILED) null else buildReleaseArchive(command, release)

        return jdbi.inTransaction<ExchangePublicationOutcome, Exception> { handle ->
            val namespace = requireNotNull(namespaceBinder.namespaceOf(handle, command.catalog))
            val existing = store.findByVersion(handle, command.catalog, release.version)
            when {
                existing == null -> ExchangePublicationOutcome(UUIDv7.generate(), release.version, ExchangePublicationOutcome.Status.QUEUED).also { outcome ->
                    val id = outcome.publicationId
                    store.insert(
                        handle = handle,
                        id = id,
                        catalog = command.catalog,
                        version = release.version,
                        fingerprint = release.fingerprint,
                        namespace = namespace,
                        archive = requireNotNull(archive),
                        idempotencyKey = UUIDv7.generate(),
                    )
                }

                else -> repeated(existing.id, release.version, existing.status) ?: run {
                    validate(
                        "publication",
                        existing.status == CatalogPublicationStatus.CANCELLED || existing.archiveRetained,
                        ValidationCode.PUBLICATION_ARCHIVE_MISSING,
                    ) { "The failed publication no longer has its released archive; publish a new release instead." }
                    store.requeue(handle, existing.id, namespace, UUIDv7.generate(), archive)
                    ExchangePublicationOutcome(existing.id, release.version, ExchangePublicationOutcome.Status.QUEUED)
                }
            }
        }
    }

    /**
     * What a repeat of a publication in [status] answers, or null when it is to be queued (again): a
     * first attempt, or a failed or cancelled one. Queued or being sent answers that, accepted answers
     * published; a rejected one is refused, since the Exchange will not take that number again.
     */
    private fun repeated(id: UUID, version: String, status: CatalogPublicationStatus): ExchangePublicationOutcome? = when {
        status.isActive -> ExchangePublicationOutcome(id, version, ExchangePublicationOutcome.Status.ALREADY_QUEUED)

        status == CatalogPublicationStatus.ACCEPTED -> ExchangePublicationOutcome(id, version, ExchangePublicationOutcome.Status.ALREADY_PUBLISHED)

        status == CatalogPublicationStatus.REJECTED -> throw ValidationException(
            "publication",
            "Epistola Exchange rejected v$version; that number cannot be published again. Release a new version instead.",
            ValidationCode.PUBLICATION_REJECTED,
        )

        else -> null
    }

    /**
     * The bytes of [release]: publishing v1.0.0 is publishing what v1.0.0 was, whatever has been
     * edited since. The fingerprint is recomputed and a mismatch refuses, the same guard the release
     * export applies -- it should be impossible, and submitting an archive whose contents contradict
     * the fingerprint it advertises would spread that rather than stop it.
     */
    private fun buildReleaseArchive(command: PublishCurrentCatalogRelease, release: Release): ByteArray {
        // Checked against what identifies it, and named by its content hash (ADR 0034).
        val content = requireNotNull(identity.travelling(command.catalog, release.version)) {
            "Release ${release.version} of catalog '${command.catalogKey.value}' is gone"
        }
        return archiveBuilder.build(content.content, content.release)
    }

    /**
     * The release to send: the one named, or the one the catalog is currently on.
     *
     */
    private fun releaseToPublish(command: PublishCurrentCatalogRelease): Release {
        val release = findRelease(command) ?: throw ValidationException(
            "catalogKey",
            command.version?.let { "This catalog has no release $it to publish." } ?: "This catalog has no release to publish.",
            ValidationCode.PUBLICATION_NO_RELEASE,
        )
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
            JOIN catalogs c ON c.tenant_key = r.tenant_key AND c.catalog_id = r.catalog_id
            WHERE r.tenant_key = :tenantKey AND c.catalog_id = epistola_catalog_id(:tenantKey, :catalogKey_ns, :catalogKey)
              $selection
            """,
        ).apply { if (command.version != null) bind("version", command.version) }
            .bind("tenantKey", command.tenantKey).bindCatalog("catalogKey", command.catalog).map { rs, _ ->
                Release(
                    rs.getString("version"),
                    rs.getString("fingerprint"),
                    rs.getObject("released_at", OffsetDateTime::class.java),
                )
            }.findOne().orElse(null)
    }

    private data class Release(val version: String, val fingerprint: String, val releasedAt: OffsetDateTime)
}
