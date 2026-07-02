package app.epistola.suite.catalog.commands

import app.epistola.suite.catalog.AuthType
import app.epistola.suite.catalog.CatalogClient
import app.epistola.suite.catalog.CatalogImportContext
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.SelfManagedTransaction
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.security.SystemInternal
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Idempotently brings a SUBSCRIBED catalog (identified by its manifest slug at
 * [sourceUrl]) to the bundled/remote content: registers + installs it on first
 * sight, no-ops when the installed content fingerprint already matches, and
 * upgrades when it drifted. The single state machine shared by the system
 * catalog installer ([InstallSystemCatalog][app.epistola.suite.catalog.system.InstallSystemCatalog])
 * and the demo loader — previously duplicated in both.
 *
 * Runs inside `CatalogImportContext.runAsImport { … }` so `requireCatalogEditable`
 * short-circuits (SUBSCRIBED catalogs are read-only to tenants, but the import
 * path writes through them). `SystemInternal` — callers are framework code
 * (tenant creation, boot loaders), not user requests.
 */
data class EnsureSubscribedCatalog(
    val tenantKey: TenantKey,
    val sourceUrl: String,
    val authType: AuthType = AuthType.NONE,
    val authCredential: String? = null,
) : Command<EnsureSubscribedCatalogResult>,
    SystemInternal,
    // Fetches the remote catalog over HTTP mid-command.
    SelfManagedTransaction

enum class EnsureCatalogStatus { INSTALLED, UPGRADED, ALREADY_CURRENT }

data class EnsureSubscribedCatalogResult(
    val status: EnsureCatalogStatus,
    val catalogKey: CatalogKey,
    val previousVersion: String?,
    val newVersion: String,
)

@Component
class EnsureSubscribedCatalogHandler(
    private val catalogClient: CatalogClient,
) : CommandHandler<EnsureSubscribedCatalog, EnsureSubscribedCatalogResult> {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun handle(command: EnsureSubscribedCatalog): EnsureSubscribedCatalogResult = CatalogImportContext.runAsImport {
        // The manifest's content fingerprint is the gate that decides
        // install / upgrade / no-op (version is for humans).
        val manifest = catalogClient.fetchManifest(command.sourceUrl, command.authType, command.authCredential)
        val catalogKey = CatalogKey.of(manifest.catalog.slug)
        val bundledVersion = manifest.release.version
        val bundledFingerprint = manifest.release.fingerprint

        val existing = GetCatalog(command.tenantKey, catalogKey).query()

        if (existing == null) {
            val catalog = RegisterCatalog(
                tenantKey = command.tenantKey,
                sourceUrl = command.sourceUrl,
                authType = command.authType,
                authCredential = command.authCredential,
            ).execute()
            val results = InstallFromCatalog(tenantKey = command.tenantKey, catalogKey = catalog.id).execute()
            val failed = results.filter { it.status == InstallStatus.FAILED }
            if (failed.isNotEmpty()) {
                log.warn(
                    "Catalog '{}' installed for tenant {} with {} failures: {}",
                    catalogKey.value,
                    command.tenantKey.value,
                    failed.size,
                    failed.joinToString { "${it.type}/${it.slug}: ${it.errorMessage}" },
                )
            }
            log.info("Catalog '{}' installed for tenant {} at version {}", catalogKey.value, command.tenantKey.value, bundledVersion)
            return@runAsImport EnsureSubscribedCatalogResult(EnsureCatalogStatus.INSTALLED, catalogKey, null, bundledVersion)
        }

        // Fingerprint is the change gate. A manifest without a fingerprint
        // (legacy / hand-rolled external) can't be drift-detected, so fall
        // back to the version string instead of re-upgrading on every boot.
        val alreadyCurrent = if (bundledFingerprint != null) {
            existing.installedFingerprint == bundledFingerprint
        } else {
            log.warn(
                "Catalog '{}' manifest has no release.fingerprint — using version-string change detection (no content drift detection) for tenant {}",
                catalogKey.value,
                command.tenantKey.value,
            )
            existing.installedReleaseVersion == bundledVersion
        }
        if (alreadyCurrent) {
            return@runAsImport EnsureSubscribedCatalogResult(
                EnsureCatalogStatus.ALREADY_CURRENT,
                catalogKey,
                existing.installedReleaseVersion,
                bundledVersion,
            )
        }

        val previousVersion = existing.installedReleaseVersion
        val upgrade = UpgradeCatalog(tenantKey = command.tenantKey, catalogKey = existing.id).execute()
        if (upgrade.aborted) {
            // Loud + self-retrying: SystemCatalogBootstrap's catch counts this
            // as failed; the version stayed put so the next boot retries.
            throw CatalogUpgradeAbortedException(catalogKey, upgrade.installResults.filter { it.status == InstallStatus.FAILED })
        }
        log.info(
            "Catalog '{}' upgraded for tenant {}: {} -> {}",
            catalogKey.value,
            command.tenantKey.value,
            previousVersion,
            bundledVersion,
        )
        EnsureSubscribedCatalogResult(EnsureCatalogStatus.UPGRADED, catalogKey, previousVersion, bundledVersion)
    }
}
