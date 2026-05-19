package app.epistola.suite.catalog.system

import app.epistola.suite.catalog.AuthType
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.commands.EnsureCatalogStatus
import app.epistola.suite.catalog.commands.EnsureSubscribedCatalog
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.fonts.commands.EnsureSystemFonts
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.mediator.execute
import app.epistola.suite.security.EpistolaPrincipal
import app.epistola.suite.security.PlatformRole
import app.epistola.suite.security.SecurityContext
import app.epistola.suite.security.SystemInternal
import app.epistola.suite.security.SystemUser
import app.epistola.suite.security.TenantRole
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
 * Idempotent installer for the bundled system catalog. Two callers:
 *
 *  - `CreateTenant` invokes it inside its `@Transactional` boundary for
 *    each new tenant, so a tenant never exists without its system catalog.
 *  - `SystemCatalogBootstrap` walks every tenant on application start;
 *    each call is a cheap fingerprint-comparison + no-op in steady state.
 *
 * A thin system-catalog-specific wrapper over the shared
 * [EnsureSubscribedCatalog] state machine: it adds the elevated install
 * principal and seeds the bundled system fonts (which live next to the
 * system catalog and are re-seeded every pass, idempotently, so a newly
 * bundled font is picked up even when the catalog content didn't move).
 *
 * Result mirrors that flow: `INSTALLED` (first-time), `ALREADY_CURRENT`
 * (no-op), or `UPGRADED` (bundle content drifted past what's installed).
 *
 * Marked `SystemInternal` because the caller is framework code
 * (`CreateTenant`), not a user-initiated request.
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
 * Synthetic principal used while the installer dispatches its inner commands
 * (`RegisterCatalog`, `InstallFromCatalog`, …). The outer `InstallSystemCatalog`
 * is `SystemInternal`, but the per-resource imports it fans out to require
 * tenant permissions — and `InstallSystemCatalog` may be invoked from
 * `CreateTenant` on behalf of a user with the platform role to create tenants
 * but no membership in the just-created tenant. Re-binding the security context
 * for the duration lets those inner commands authorize against a principal that
 * always has access.
 */
private val SYSTEM_INSTALL_PRINCIPAL = EpistolaPrincipal(
    userId = SystemUser.ID,
    externalId = SystemUser.EXTERNAL_ID,
    email = SystemUser.EMAIL,
    displayName = SystemUser.DISPLAY_NAME,
    tenantMemberships = emptyMap(),
    globalRoles = TenantRole.entries.toSet(),
    platformRoles = setOf(PlatformRole.TENANT_MANAGER),
    currentTenantId = null,
)

@Component
class InstallSystemCatalogHandler : CommandHandler<InstallSystemCatalog, InstallSystemCatalogResult> {

    override fun handle(command: InstallSystemCatalog): InstallSystemCatalogResult = SecurityContext.runWithPrincipal(SYSTEM_INSTALL_PRINCIPAL) {
        val result = EnsureSubscribedCatalog(
            tenantKey = command.tenantKey,
            sourceUrl = SYSTEM_CATALOG_URL,
            authType = AuthType.NONE,
        ).execute()

        // Bundled font families live next to the system catalog and are seeded
        // every pass (idempotent UPSERT) — inside the same elevated principal —
        // so a newly bundled font is picked up on the next boot even when the
        // catalog content didn't move, mirroring the asset/code-list payload.
        EnsureSystemFonts(tenantKey = command.tenantKey).execute()

        InstallSystemCatalogResult(
            status = when (result.status) {
                EnsureCatalogStatus.INSTALLED -> SystemCatalogStatus.INSTALLED
                EnsureCatalogStatus.UPGRADED -> SystemCatalogStatus.UPGRADED
                EnsureCatalogStatus.ALREADY_CURRENT -> SystemCatalogStatus.ALREADY_CURRENT
            },
            installedVersion = result.newVersion,
        )
    }
}
