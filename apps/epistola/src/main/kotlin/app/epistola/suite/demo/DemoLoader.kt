package app.epistola.suite.demo

import app.epistola.suite.mediator.Mediator
import app.epistola.suite.metadata.AppMetadataService
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.tenants.commands.CreateTenant
import app.epistola.suite.tenants.commands.DeleteTenant
import app.epistola.suite.tenants.queries.ListTenants
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(
    name = ["epistola.demo.enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class DemoLoader(
    private val mediator: Mediator,
    private val metadataService: AppMetadataService,
) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        try {
            val currentVersion = metadataService.get(DEMO_VERSION_KEY) ?: "0.0.0"

            if (DEMO_VERSION != currentVersion) {
                log.info("Demo version changed: {} -> {}", currentVersion, DEMO_VERSION)
                recreateDemoTenant()
                metadataService.set(DEMO_VERSION_KEY, DEMO_VERSION)
                log.info("Demo tenant updated to version {}", DEMO_VERSION)
            } else {
                log.info("Demo version unchanged: {}", DEMO_VERSION)
            }
        } catch (e: Exception) {
            log.error("Failed to load demo: {}", e.message, e)
        }
    }

    private fun recreateDemoTenant() {
        // Find and delete existing demo tenant (if exists)
        val existingTenants = mediator.query(ListTenants())
        val demoTenant = existingTenants.find { it.name == DEMO_TENANT_NAME }

        if (demoTenant != null) {
            log.info("Deleting existing demo tenant (id={})", demoTenant.id)
            mediator.send(DeleteTenant(id = demoTenant.id))
        }

        // Create new demo tenant
        val tenant = mediator.send(CreateTenant(name = DEMO_TENANT_NAME))
        log.info("Created demo tenant: {} (id={})", tenant.name, tenant.id)

        // Create templates
        DEMO_TEMPLATES.forEach { templateName ->
            mediator.send(
                CreateDocumentTemplate(
                    tenantId = tenant.id,
                    name = templateName,
                ),
            )
        }
        log.info("Created {} demo templates", DEMO_TEMPLATES.size)
    }

    companion object {
        private const val DEMO_VERSION = "1.0.0" // Bump this to reset demo tenant
        private const val DEMO_VERSION_KEY = "demo_version"
        private const val DEMO_TENANT_NAME = "Demo Tenant"

        private val DEMO_TEMPLATES =
            listOf(
                "Invoice Template",
                "Contract Template",
                "Letter Template",
                "Report Template",
                "Proposal Template",
            )
    }
}
