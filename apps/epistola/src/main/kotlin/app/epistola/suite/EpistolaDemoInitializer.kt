package app.epistola.suite

import app.epistola.suite.mediator.Mediator
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.tenants.commands.CreateTenant
import app.epistola.suite.tenants.queries.ListTenants
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component

@Component
class EpistolaDemoInitializer(
    private val mediator: Mediator,
) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        val existingTenants = mediator.query(ListTenants())

        if (existingTenants.isEmpty()) {
            log.info("Seeding demo data...")
            seedDemoData()
            log.info("Demo data seeded successfully")
        } else {
            log.info("Found {} existing tenants, skipping demo seed", existingTenants.size)
        }
    }

    private fun seedDemoData() {
        val tenant = mediator.send(CreateTenant(name = DEMO_TENANT_NAME))
        log.info("Created demo tenant: {} (id={})", tenant.name, tenant.id)

        SEED_TEMPLATES.forEach { name ->
            mediator.send(CreateDocumentTemplate(tenantId = tenant.id, name = name))
        }
        log.info("Created {} demo templates for tenant '{}'", SEED_TEMPLATES.size, tenant.name)
    }

    companion object {
        private const val DEMO_TENANT_NAME = "Demo Tenant"

        private val SEED_TEMPLATES = listOf(
            "Invoice Template",
            "Contract Template",
            "Letter Template",
            "Report Template",
            "Proposal Template",
        )
    }
}
