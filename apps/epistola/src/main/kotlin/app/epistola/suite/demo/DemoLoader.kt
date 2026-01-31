package app.epistola.suite.demo

import app.epistola.suite.common.UUIDv7
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.metadata.AppMetadataService
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.UpdateDocumentTemplate
import app.epistola.suite.templates.commands.versions.UpdateDraft
import app.epistola.suite.templates.queries.variants.ListVariants
import app.epistola.suite.tenants.commands.CreateTenant
import app.epistola.suite.tenants.commands.DeleteTenant
import app.epistola.suite.tenants.queries.ListTenants
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.core.io.ResourceLoader
import org.springframework.core.io.support.ResourcePatternUtils
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.util.UUID

@Component
@ConditionalOnProperty(
    name = ["epistola.demo.enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class DemoLoader(
    private val mediator: Mediator,
    private val metadataService: AppMetadataService,
    private val resourceLoader: ResourceLoader,
    private val objectMapper: ObjectMapper,
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
        val tenant = mediator.send(CreateTenant(id = UUIDv7.generate(), name = DEMO_TENANT_NAME))
        log.info("Created demo tenant: {} (id={})", tenant.name, tenant.id)

        // Load and create templates from JSON definitions
        val definitions = loadTemplateDefinitions()
        log.info("Loaded {} template definitions", definitions.size)

        definitions.forEach { definition ->
            createTemplateFromDefinition(tenant.id, definition)
        }

        log.info("Created {} demo templates", definitions.size)
    }

    private fun createTemplateFromDefinition(tenantId: UUID, definition: TemplateDefinition) {
        // 1. Create template with basic metadata
        val template = mediator.send(
            CreateDocumentTemplate(
                id = UUIDv7.generate(),
                tenantId = tenantId,
                name = definition.name,
            ),
        )
        log.debug("Created template: {} (id={})", template.name, template.id)

        // 2. Update template with data model and examples
        mediator.send(
            UpdateDocumentTemplate(
                tenantId = tenantId,
                id = template.id,
                dataModel = definition.dataModel,
                dataExamples = definition.dataExamples,
                forceUpdate = true, // Skip validation warnings for demo data
            ),
        )
        log.debug("Updated template metadata for: {}", template.name)

        // 3. Get the default variant (first variant, auto-created by CreateDocumentTemplate)
        val variants = mediator.query(ListVariants(tenantId = tenantId, templateId = template.id))
        val defaultVariant = variants.firstOrNull()
            ?: error("No default variant found for template ${template.id}")

        // 4. Update the draft version with visual content
        mediator.send(
            UpdateDraft(
                tenantId = tenantId,
                templateId = template.id,
                variantId = defaultVariant.id,
                templateModel = definition.templateModel,
            ),
        )
        log.debug("Updated draft template model for: {}", template.name)
    }

    private fun loadTemplateDefinitions(): List<TemplateDefinition> {
        val resolver = ResourcePatternUtils.getResourcePatternResolver(resourceLoader)
        val resources = resolver.getResources("classpath:demo/templates/*.json")

        if (resources.isEmpty()) {
            log.warn("No demo template definitions found in classpath:demo/templates/")
            return emptyList()
        }

        return resources.mapNotNull { resource ->
            try {
                resource.inputStream.use { inputStream ->
                    objectMapper.readValue(inputStream, TemplateDefinition::class.java)
                }
            } catch (e: Exception) {
                log.error("Failed to load template from {}: {}", resource.filename, e.message)
                null
            }
        }
    }

    companion object {
        private const val DEMO_VERSION = "2.0.2" // Bump this to reset demo tenant
        private const val DEMO_VERSION_KEY = "demo_version"
        private const val DEMO_TENANT_NAME = "Demo Tenant"
    }
}
