// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.exchange

import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.CatalogSizeLimits
import app.epistola.suite.catalog.CatalogType
import app.epistola.suite.catalog.CatalogUpstreamCheckStore
import app.epistola.suite.catalog.commands.ImportCatalogZip
import app.epistola.suite.catalog.commands.ImportCatalogZipResult
import app.epistola.suite.catalog.commands.InstallStatus
import app.epistola.suite.catalog.commands.UnregisterCatalog
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.validation.ValidationCode
import app.epistola.suite.validation.ValidationException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.util.HexFormat

/**
 * Turns a release on Epistola Exchange into an installed catalog.
 *
 * Almost none of this is new machinery. An Exchange release *is* a catalog archive, so the install
 * is `ImportCatalogZip` in its SUBSCRIBED mode, which already does mirror-semantic import: stale
 * resources pruned, `installed_*` advanced, and — the part that matters most here — the whole thing
 * abandoned without advancing anything if a single resource fails, so a bad release can never leave
 * a catalog half-upgraded.
 *
 * What is new is everything before that: deciding which release, refusing the ones that would
 * collide or cannot be trusted, and doing so *before* spending a download.
 *
 * Install and upgrade are the same six steps. They differ only in whether a catalog is already
 * there, which `ImportCatalogZip` works out for itself.
 */
@Component
class ExchangeCatalogInstaller(
    private val availability: ExchangeAvailability,
    private val credentials: ExchangeCredentialService,
    private val client: ExchangeClient,
    private val sizeLimits: CatalogSizeLimits,
    private val upstreamChecks: CatalogUpstreamCheckStore,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun install(
        tenantKey: TenantKey,
        namespace: String,
        catalogKey: String,
        version: String? = null,
    ): ExchangeInstallResult {
        val connection = requireInstallable(tenantKey)
        val token = credentials.accessToken(connection)
            ?: throw unavailable("Exchange would not issue an access token for this tenant. Reconnect it on the Exchange page.")
        val baseUrl = connection.baseUrl
        val sourceUri = ExchangeSourceUri.of(namespace, catalogKey)

        val release = resolveRelease(baseUrl, token, namespace, catalogKey, version)
        // Whether this catalog is already here decides what an abort has to undo, so it is
        // established before anything is fetched rather than inferred afterwards.
        val existedBefore = GetCatalog(tenantKey, CatalogKey.of(catalogKey)).query() != null
        requireNoLocalConflict(tenantKey, catalogKey, sourceUri)
        requireImportableSize(release)

        val archive = client.downloadArchive(baseUrl, token, namespace, catalogKey, release.version, sizeLimits.maxZipSize.toBytes())
        verifyDigest(archive, release)

        val imported = ImportCatalogZip(
            tenantKey = tenantKey,
            zipBytes = archive,
            catalogType = CatalogType.SUBSCRIBED,
            sourceUrl = sourceUri,
        ).execute()

        if (imported.aborted) {
            // Nothing was advanced, so nothing about the upstream state should be either: the next
            // run must retry this same release rather than believe it is installed.
            log.warn(
                "Install of {} {} into tenant {} aborted; {} resource(s) failed",
                sourceUri,
                release.version,
                tenantKey.value,
                imported.results.count { it.status == InstallStatus.FAILED },
            )
            // The import creates the catalog row before it installs anything into it, and an abort
            // does not undo that — so a first install that fails would otherwise leave an empty
            // catalog behind, claiming nothing had changed while occupying the ID that the retry
            // needs. Only a catalog this attempt brought into being is removed; an upgrade that
            // aborts leaves the existing catalog exactly where it was.
            if (!existedBefore) {
                UnregisterCatalog(tenantKey, imported.catalogKey, force = true).execute()
            }
            return ExchangeInstallResult(imported, release, aborted = true, rolledBack = !existedBefore)
        }

        upstreamChecks.recordInstalled(tenantKey, imported.catalogKey, release.version, release.sha256)
        return ExchangeInstallResult(imported, release, aborted = false)
    }

    private fun requireInstallable(tenantKey: TenantKey): ExchangeTenantConnection {
        if (!availability.isInstallAvailable(tenantKey)) {
            throw unavailable("Installing catalogs from Epistola Exchange is not enabled for this tenant.")
        }
        // Exchange does not require a token for these routes today, but this installation does: it
        // is what gives an install an identity, an organization, and somewhere to record where the
        // catalog came from — and Exchange is expected to require one before long.
        return credentials.activeConnection(tenantKey)
            ?: throw unavailable("This tenant is not connected to Epistola Exchange. Connect it on the Exchange page first.")
    }

    /**
     * Picks the release to install: the one asked for, or the newest anyone may install.
     *
     * A version named explicitly is still checked rather than trusted. Exchange keeps returning a
     * blocked or withdrawn release to the installation that publishes the catalog, so for a tenant
     * that both publishes and installs, "Exchange returned it" is not the same as "it is offered".
     */
    private fun resolveRelease(
        baseUrl: String,
        token: String,
        namespace: String,
        catalogKey: String,
        version: String?,
    ): ExchangeCatalogRelease {
        val releases = client.releases(baseUrl, token, namespace, catalogKey)
        if (version == null) {
            return releases.firstOrNull { it.installable }
                ?: throw ValidationException(
                    "version",
                    "Epistola Exchange has no installable release of $namespace/$catalogKey.",
                    ValidationCode.EXCHANGE_RELEASE_UNAVAILABLE,
                )
        }
        val chosen = releases.firstOrNull { it.version == version }
            ?: throw ValidationException(
                "version",
                "Epistola Exchange does not offer version $version of $namespace/$catalogKey.",
                ValidationCode.EXCHANGE_RELEASE_UNAVAILABLE,
            )
        if (!chosen.installable) {
            throw ValidationException(
                "version",
                "Version $version of $namespace/$catalogKey is ${chosen.availability.lowercase()} on Epistola Exchange and cannot be installed.",
                ValidationCode.EXCHANGE_RELEASE_UNAVAILABLE,
            )
        }
        return chosen
    }

    /**
     * Refuses an install that would land on a catalog that is already something else.
     *
     * A catalog is addressed within a tenant by the slug in its own manifest, so `acme/invoices` and
     * `globex/invoices` both want to be `invoices` here. Importing over an authored catalog is
     * already refused as a type flip, but two *subscribed* catalogs are the same type — so without
     * this the second install would quietly overwrite the first, which is worse than failing.
     *
     * Checked before downloading: a collision costs no bandwidth to discover.
     */
    private fun requireNoLocalConflict(tenantKey: TenantKey, catalogKey: String, sourceUri: String) {
        val localKey = CatalogKey.of(catalogKey)
        val existing = GetCatalog(tenantKey, localKey).query() ?: return
        if (existing.sourceUrl == sourceUri) return

        val heldBy = when {
            existing.type == CatalogType.AUTHORED -> "an authored catalog"
            ExchangeSourceUri.matches(existing.sourceUrl) ->
                "a catalog installed from ${ExchangeSourceUri.parse(existing.sourceUrl)}"
            existing.sourceUrl != null -> "a catalog subscribed from ${existing.sourceUrl}"
            else -> "a catalog imported from a ZIP"
        }
        throw ValidationException(
            "catalogKey",
            "This tenant already uses the catalog ID '${localKey.value}' for $heldBy. " +
                "A catalog is addressed by that ID, so remove it before installing $sourceUri, " +
                "or install into a different tenant.",
            ValidationCode.EXCHANGE_CATALOG_KEY_TAKEN,
        )
    }

    /**
     * Refuses an oversized release from its advertised size, before any of it is fetched.
     *
     * The download caps itself as well, because this figure is Exchange's claim. This check exists
     * so the refusal arrives immediately and names the release, rather than after a long transfer.
     */
    private fun requireImportableSize(release: ExchangeCatalogRelease) {
        val max = sizeLimits.maxZipSize.toBytes()
        if (release.sizeBytes > max) {
            throw ValidationException(
                "version",
                "Release ${release.version} is ${release.sizeBytes / 1024 / 1024} MB, larger than the " +
                    "${max / 1024 / 1024} MB this installation imports. Raise epistola.catalog.max-zip-size to install it.",
                ValidationCode.EXCHANGE_ARCHIVE_REJECTED,
            )
        }
    }

    /**
     * The bytes must be the bytes Exchange said it was serving.
     *
     * Checked against the digest from the release *listing* rather than the `ETag` on the download,
     * so the claim being verified came from a different response than the body.
     */
    private fun verifyDigest(archive: ByteArray, release: ExchangeCatalogRelease) {
        val actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(archive))
        if (!actual.equals(release.sha256, ignoreCase = true)) {
            throw ValidationException(
                "archive",
                "The archive Epistola Exchange served for ${release.version} does not match the digest it published.",
                ValidationCode.EXCHANGE_ARCHIVE_REJECTED,
            )
        }
    }

    private fun unavailable(message: String) = ValidationException("exchange", message, ValidationCode.EXCHANGE_INSTALL_UNAVAILABLE)
}

/**
 * What an install did.
 *
 * [aborted] carries `ImportCatalogZip`'s own guarantee outward: a resource failed, so nothing was
 * pruned, no version was advanced, and the catalog is exactly as it was.
 */
data class ExchangeInstallResult(
    val imported: ImportCatalogZipResult,
    val release: ExchangeCatalogRelease,
    val aborted: Boolean,
    /**
     * Whether the aborted attempt also removed the catalog it had just created.
     *
     * True for a first install, which leaves nothing behind. False for an upgrade, where the
     * catalog was already there and stays on the release it was running — so the two cases can be
     * described accurately rather than both claiming nothing happened.
     */
    val rolledBack: Boolean = false,
)
