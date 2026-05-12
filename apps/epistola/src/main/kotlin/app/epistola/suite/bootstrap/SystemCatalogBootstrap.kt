package app.epistola.suite.bootstrap

import app.epistola.suite.catalog.system.InstallSystemCatalog
import app.epistola.suite.catalog.system.SystemCatalogStatus
import app.epistola.suite.common.ids.UserKey
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.mediator.MediatorContext
import app.epistola.suite.security.EpistolaPrincipal
import app.epistola.suite.security.PlatformRole
import app.epistola.suite.security.SecurityContext
import app.epistola.suite.security.TenantRole
import app.epistola.suite.tenants.queries.ListTenants
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Boot-time auto-upgrade for the bundled system catalog. Walks every
 * tenant on application start and runs `InstallSystemCatalog`.
 *
 * In a steady state the call is `ALREADY_CURRENT` for every tenant and
 * does one cheap version-comparison query each. The work only kicks in
 * when the bundled manifest's `release.version` has been bumped since
 * the last deploy: the installer then dispatches `UpgradeCatalog`, which
 * re-installs the system catalog's resources at the new version and
 * advances `installed_release_version`.
 *
 * Ordered after `DemoLoader` so the demo tenant (created on boot when
 * `epistola.demo.enabled=true`) is also picked up on the same pass.
 */
@Component
@Order(SystemCatalogBootstrap.RUN_ORDER)
class SystemCatalogBootstrap(
    private val mediator: Mediator,
) : ApplicationRunner {

    companion object {
        const val RUN_ORDER = 100

        private fun deterministicUserId(username: String): UserKey = UserKey.of(UUID.nameUUIDFromBytes(username.toByteArray(StandardCharsets.UTF_8)))

        /** Bootstrap principal — has full access to every tenant. */
        private val SYSTEM_PRINCIPAL = EpistolaPrincipal(
            userId = deterministicUserId("system@epistola.app"),
            externalId = "system",
            email = "system@epistola.app",
            displayName = "System",
            tenantMemberships = emptyMap(),
            globalRoles = TenantRole.entries.toSet(),
            platformRoles = PlatformRole.entries.toSet(),
            currentTenantId = null,
        )
    }

    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        try {
            SecurityContext.runWithPrincipal(SYSTEM_PRINCIPAL) {
                MediatorContext.runWithMediator(mediator) {
                    upgradeAll()
                }
            }
        } catch (e: Exception) {
            log.error("System catalog auto-upgrade failed: {}", e.message, e)
        }
    }

    private fun upgradeAll() {
        val tenants = mediator.query(ListTenants())
        if (tenants.isEmpty()) return

        var upgraded = 0
        var current = 0
        var installed = 0
        var failed = 0

        for (tenant in tenants) {
            try {
                val result = mediator.send(InstallSystemCatalog(tenant.id))
                when (result.status) {
                    SystemCatalogStatus.UPGRADED -> upgraded++
                    SystemCatalogStatus.ALREADY_CURRENT -> current++
                    SystemCatalogStatus.INSTALLED -> installed++
                }
            } catch (e: Exception) {
                failed++
                log.error(
                    "System catalog auto-upgrade failed for tenant '{}': {}",
                    tenant.id.value,
                    e.message,
                    e,
                )
            }
        }

        // Only log when there's anything other than steady-state to report;
        // a clean boot on a stable bundle is the common case and would
        // otherwise produce a noisy "checked N tenants" line every restart.
        if (upgraded > 0 || installed > 0 || failed > 0) {
            log.info(
                "System catalog auto-upgrade — upgraded: {}, installed: {}, already current: {}, failed: {}",
                upgraded,
                installed,
                current,
                failed,
            )
        }
    }
}
