package app.epistola.suite.catalog.system

import app.epistola.suite.catalog.AuthType
import app.epistola.suite.catalog.CatalogClient
import app.epistola.suite.catalog.CatalogImportContext
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.commands.InstallFromCatalog
import app.epistola.suite.catalog.commands.RegisterCatalog
import app.epistola.suite.catalog.commands.UpgradeCatalog
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.UserKey
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.security.EpistolaPrincipal
import app.epistola.suite.security.PlatformRole
import app.epistola.suite.security.SecurityContext
import app.epistola.suite.security.SystemInternal
import app.epistola.suite.security.TenantRole
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * URL of the bundled system catalog. Spring's `ResourceLoader` resolves the
 * `classpath:` prefix to a resource on the runtime classpath, the same way
 * the demo catalog is loaded.
 */
const val SYSTEM_CATALOG_URL = "classpath:epistola/catalogs/system/catalog.json"

/** Slug of the system catalog row inserted into every tenant. */
val SYSTEM_CATALOG_KEY = CatalogKey.of("system")

/**
 * Idempotent installer for the bundled system catalog. Run by
 * [`SystemCatalogBootstrap`][app.epistola.suite.catalog.system.SystemCatalogBootstrap]
 * on application start (back-fills every tenant) and by `CreateTenant`
 * (initial population for newly-created tenants).
 *
 * Flow:
 *  - First-time install: `RegisterCatalog` + `InstallFromCatalog`.
 *  - Already installed at the bundled version: no-op.
 *  - Already installed at an older version: `UpgradeCatalog`.
 *
 * The whole flow runs inside `CatalogImportContext.runAsImport { … }` so
 * `requireCatalogEditable` short-circuits — the SYSTEM catalog is SUBSCRIBED
 * (read-only to tenants) but the import path writes through it.
 *
 * Marked `SystemInternal` because callers are framework code (boot runners
 * and `CreateTenant`), not user-initiated requests.
 */
data class InstallSystemCatalog(
    val tenantKey: TenantKey,
) : Command<InstallSystemCatalogResult>,
    SystemInternal

enum class SystemCatalogStatus { INSTALLED, UPGRADED, ALREADY_CURRENT }

data class InstallSystemCatalogResult(
    val status: SystemCatalogStatus,
    val installedVersion: String,
)

/**
 * Synthetic principal used while the system catalog installer dispatches its
 * inner commands (`RegisterCatalog`, `InstallFromCatalog`, …). The outer
 * `InstallSystemCatalog` is `SystemInternal`, but the per-resource imports
 * it fans out to require tenant permissions — and `InstallSystemCatalog` may
 * be invoked from `CreateTenant` on behalf of a user who has the platform
 * role to create tenants but no membership in the just-created tenant.
 * Re-binding the security context for the duration of the install lets those
 * inner commands authorize against a principal that always has access.
 */
private val SYSTEM_INSTALL_PRINCIPAL = EpistolaPrincipal(
    userId = UserKey.of("00000000-0000-0000-0000-000000000002"),
    externalId = "system-catalog-installer",
    email = "system-catalog@epistola.local",
    displayName = "System Catalog Installer",
    tenantMemberships = emptyMap(),
    globalRoles = TenantRole.entries.toSet(),
    platformRoles = setOf(PlatformRole.TENANT_MANAGER),
    currentTenantId = null,
)

@Component
class InstallSystemCatalogHandler(
    private val catalogClient: CatalogClient,
) : CommandHandler<InstallSystemCatalog, InstallSystemCatalogResult> {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun handle(command: InstallSystemCatalog): InstallSystemCatalogResult = SecurityContext.runWithPrincipal(SYSTEM_INSTALL_PRINCIPAL) {
        doHandle(command)
    }

    private fun doHandle(command: InstallSystemCatalog): InstallSystemCatalogResult = CatalogImportContext.runAsImport {
        // Fetching once up front — the manifest's release.version is the
        // gate that decides install / upgrade / no-op.
        val manifest = catalogClient.fetchManifest(SYSTEM_CATALOG_URL, AuthType.NONE, null)
        val bundledVersion = manifest.release.version

        val existing = GetCatalog(command.tenantKey, SYSTEM_CATALOG_KEY).query()

        if (existing == null) {
            // First-time install for this tenant.
            val catalog = RegisterCatalog(
                tenantKey = command.tenantKey,
                sourceUrl = SYSTEM_CATALOG_URL,
                authType = AuthType.NONE,
                authCredential = null,
            ).execute()
            val results = InstallFromCatalog(
                tenantKey = command.tenantKey,
                catalogKey = catalog.id,
            ).execute()
            val failed = results.count { it.status == app.epistola.suite.catalog.commands.InstallStatus.FAILED }
            if (failed > 0) {
                log.warn(
                    "System catalog installed for tenant {} with {} failures: {}",
                    command.tenantKey.value,
                    failed,
                    results.filter { it.status == app.epistola.suite.catalog.commands.InstallStatus.FAILED }
                        .joinToString { "${it.type}/${it.slug}: ${it.errorMessage}" },
                )
            }
            log.info("System catalog installed for tenant {} at version {}", command.tenantKey.value, bundledVersion)
            return@runAsImport InstallSystemCatalogResult(SystemCatalogStatus.INSTALLED, bundledVersion)
        }

        if (existing.installedReleaseVersion == bundledVersion) {
            return@runAsImport InstallSystemCatalogResult(SystemCatalogStatus.ALREADY_CURRENT, bundledVersion)
        }

        // Existing install at an older version — upgrade. `UpgradeCatalog`
        // re-runs the install dispatch and bumps `installed_release_version`.
        val previousVersion = existing.installedReleaseVersion
        UpgradeCatalog(tenantKey = command.tenantKey, catalogKey = existing.id).execute()
        log.info(
            "System catalog upgraded for tenant {}: {} -> {}",
            command.tenantKey.value,
            previousVersion,
            bundledVersion,
        )
        InstallSystemCatalogResult(SystemCatalogStatus.UPGRADED, bundledVersion)
    }
}
