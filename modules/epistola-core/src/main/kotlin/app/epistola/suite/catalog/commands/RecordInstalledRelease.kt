// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.commands

import app.epistola.catalog.protocol.ReleaseInfo
import app.epistola.generation.pdf.RenderingDefaults
import app.epistola.suite.catalog.CatalogContentBuilder
import app.epistola.suite.catalog.CatalogFingerprintService
import app.epistola.suite.catalog.CatalogType
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.catalog.revisions.ReleaseDependencyStore
import app.epistola.suite.catalog.revisions.ReleaseEntryStore
import app.epistola.suite.catalog.revisions.ResourceRevisionStore
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.query
import app.epistola.suite.security.SystemInternal
import app.epistola.suite.tenants.queries.GetTenant
import org.jdbi.v3.core.Jdbi
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.time.OffsetDateTime

/**
 * Records the installed version of a subscribed catalog as a release of it, with the content that
 * install put in place.
 *
 * Generation renders releases, so a subscribed catalog needs one: its publisher's release arrives
 * as an installed mirror, and this is what turns the mirror into a release that can be rendered.
 * The release carries the publisher's version and fingerprint, not a recomputation over the mirror,
 * so it is recognisably the publisher's release.
 *
 * Idempotent: a release already recorded for the installed version is left alone. Does nothing for
 * an authored catalog, which releases itself, or one with no installed version.
 *
 * `SystemInternal`: dispatched by the install, upgrade and import commands once their own
 * authorization has passed, and by the bundled-catalog bootstrap, which runs as the system.
 *
 * @return the version recorded or already present, or null when there is nothing to record.
 */
data class RecordInstalledRelease(
    val tenantKey: TenantKey,
    val catalogKey: CatalogKey,
) : Command<String?>,
    SystemInternal

/**
 * Lets the bundled-catalog bootstrap record a catalog's release itself, after seeding what its
 * manifest does not carry (the system catalog's fonts), instead of at the end of each install step.
 */
object InstalledReleaseRecording {
    private val deferred: ScopedValue<Boolean> = ScopedValue.newInstance()

    val isDeferred: Boolean get() = deferred.isBound && deferred.get()

    fun <T> deferred(block: () -> T): T = ScopedValue.where(deferred, true).call<T, RuntimeException>(block)
}

@Component
class RecordInstalledReleaseHandler(
    private val jdbi: Jdbi,
    private val contentBuilder: CatalogContentBuilder,
    private val fingerprintService: CatalogFingerprintService,
    private val revisionStore: ResourceRevisionStore,
    private val releaseEntryStore: ReleaseEntryStore,
    private val dependencyStore: ReleaseDependencyStore,
    private val objectMapper: ObjectMapper,
) : CommandHandler<RecordInstalledRelease, String?> {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun handle(command: RecordInstalledRelease): String? {
        val catalog = GetCatalog(command.tenantKey, command.catalogKey).query() ?: return null
        if (catalog.type != CatalogType.SUBSCRIBED) return null
        val version = catalog.installedReleaseVersion ?: return null

        val exists = jdbi.withHandle<Boolean, Exception> { handle ->
            handle.createQuery("SELECT EXISTS (SELECT 1 FROM catalog_releases WHERE tenant_key = :t AND catalog_key = :c AND version = :v)")
                .bind("t", command.tenantKey)
                .bind("c", command.catalogKey)
                .bind("v", version)
                .mapTo(Boolean::class.java)
                .one()
        }
        if (exists) return version

        val content = contentBuilder.build(command.tenantKey, command.catalogKey)
        val fingerprint = catalog.installedFingerprint ?: fingerprintService.fingerprint(content)
        // The publisher's per-resource digests where the install recorded them; a resource the
        // manifest does not carry (the system catalog's seeded fonts) gets its own.
        val resourceFingerprints = fingerprintService.perResourceFingerprints(content) + catalog.installedResourceFingerprints.orEmpty()

        val tenantDefaultThemeCatalog = GetTenant(command.tenantKey).query()?.takeIf { it.defaultThemeKey != null }?.defaultThemeCatalogKey
        val referenced = dependencyStore.referencedCatalogs(content, command.catalogKey, tenantDefaultThemeCatalog)

        jdbi.useTransaction<Exception> { handle ->
            // A dependency without a release is not pinned rather than refused: refusing would fail
            // the install of a publisher's release over a gap on this installation. Rendering falls
            // back to the dependency's latest release for a catalog it did not pin.
            val direct = referenced.mapNotNull { dependency ->
                dependencyStore.latestRetainedRelease(handle, command.tenantKey, dependency)?.let { dependency to it }
            }.toMap()
            val pins = dependencyStore.closure(handle, command.tenantKey, command.catalogKey, direct)
            val releasedAt = handle.createQuery("SELECT NOW()").mapTo(OffsetDateTime::class.java).one()
            val manifest = content.toManifest(ReleaseInfo(version = version, releasedAt = releasedAt.toString(), fingerprint = fingerprint))

            handle.createUpdate(
                """
                INSERT INTO catalog_releases (tenant_key, catalog_key, version, fingerprint, notes, manifest_snapshot, released_at,
                                              content_retained, rendering_defaults_version)
                VALUES (:t, :c, :version, :fingerprint, :notes, CAST(:snapshot AS JSONB), :releasedAt, TRUE, :renderingDefaults)
                """,
            )
                .bind("t", command.tenantKey)
                .bind("c", command.catalogKey)
                .bind("version", version)
                .bind("fingerprint", fingerprint)
                .bind("notes", "Installed from ${catalog.sourceUrl ?: "a catalog archive"}")
                .bind("snapshot", objectMapper.writeValueAsString(manifest))
                .bind("releasedAt", releasedAt)
                .bind("renderingDefaults", RenderingDefaults.CURRENT.version)
                .execute()

            val revisionDigests = revisionStore.retain(handle, command.tenantKey, content)
            releaseEntryStore.record(handle, command.tenantKey, command.catalogKey, version, content, revisionDigests, resourceFingerprints)
            dependencyStore.record(handle, command.tenantKey, command.catalogKey, version, pins)
        }

        logger.info("Recorded installed release {}@{} for tenant {}", command.catalogKey.value, version, command.tenantKey.value)
        return version
    }
}
