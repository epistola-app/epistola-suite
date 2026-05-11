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
 * Boot-time back-fill for the bundled system catalog. Walks every tenant and
 * runs `InstallSystemCatalog` — idempotent on subsequent boots, populates
 * tenants created before this feature shipped (where `CreateTenant` didn't
 * yet hook in the install), and applies upgrades when the bundled manifest's
 * `release.version` bumps.
 *
 * Ordered after `DemoLoader` (which creates the demo tenant on boot) so the
 * back-fill sees every tenant the suite intends to manage on this boot.
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
                    backfillAll()
                }
            }
        } catch (e: Exception) {
            log.error("System catalog bootstrap failed: {}", e.message, e)
        }
    }

    private fun backfillAll() {
        val tenants = mediator.query(ListTenants())
        if (tenants.isEmpty()) {
            log.info("System catalog bootstrap: no tenants to back-fill.")
            return
        }

        log.info("System catalog bootstrap: checking {} tenant(s).", tenants.size)

        var installed = 0
        var upgraded = 0
        var current = 0
        var failed = 0

        for (tenant in tenants) {
            try {
                val result = mediator.send(InstallSystemCatalog(tenant.id))
                when (result.status) {
                    SystemCatalogStatus.INSTALLED -> installed++
                    SystemCatalogStatus.UPGRADED -> upgraded++
                    SystemCatalogStatus.ALREADY_CURRENT -> current++
                }
            } catch (e: Exception) {
                failed++
                log.error(
                    "System catalog bootstrap failed for tenant '{}': {}",
                    tenant.id.value,
                    e.message,
                    e,
                )
            }
        }

        log.info(
            "System catalog bootstrap done — installed: {}, upgraded: {}, already current: {}, failed: {}",
            installed,
            upgraded,
            current,
            failed,
        )
    }
}
