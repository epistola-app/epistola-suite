package app.epistola.suite.demo

import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.metadata.AppMetadataService
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.UpdateDocumentTemplate
import app.epistola.suite.templates.commands.versions.UpdateDraft
import app.epistola.suite.templates.model.DocumentStyles
import app.epistola.suite.templates.queries.variants.ListVariants
import app.epistola.suite.tenants.commands.CreateTenant
import app.epistola.suite.tenants.commands.DeleteTenant
import app.epistola.suite.tenants.commands.SetTenantDefaultTheme
import app.epistola.suite.tenants.queries.ListTenants
import app.epistola.suite.themes.commands.CreateTheme
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.core.io.ResourceLoader
import org.springframework.core.io.support.ResourcePatternUtils
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper

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
    private val transactionTemplate: TransactionTemplate,
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
        transactionTemplate.executeWithoutResult {
            // Find and delete existing demo tenant (if exists)
            val existingTenants = mediator.query(ListTenants())
            val demoTenant = existingTenants.find { it.name == DEMO_TENANT_NAME }

            if (demoTenant != null) {
                log.info("Deleting existing demo tenant (id={})", demoTenant.id)
                mediator.send(DeleteTenant(id = demoTenant.id))
            }

            // Create new demo tenant (CreateTenant now auto-creates a "Tenant Default" theme)
            val tenant = mediator.send(CreateTenant(id = TenantId.of(DEMO_TENANT_ID), name = DEMO_TENANT_NAME))
            log.info("Created demo tenant: {} (id={})", tenant.name, tenant.id)
            log.info("Tenant has default theme: {}", tenant.defaultThemeId)

            // Create additional demo themes
            val corporateThemeId = createDemoThemes(tenant.id)

            // Set "Corporate" as the default theme instead of the auto-created "Tenant Default"
            if (corporateThemeId != null) {
                mediator.send(SetTenantDefaultTheme(tenantId = tenant.id, themeId = corporateThemeId))
                log.info("Set Corporate theme as tenant default")
            }

            // Load and create templates from JSON definitions
            val definitions = loadTemplateDefinitions()
            log.info("Loaded {} template definitions", definitions.size)

            definitions.forEach { definition ->
                createTemplateFromDefinition(tenant.id, definition)
            }

            log.info("Created {} demo templates", definitions.size)
        }
    }

    /**
     * Creates demo themes and returns the ID of the Corporate theme.
     */
    private fun createDemoThemes(tenantId: TenantId): ThemeId? {
        // Corporate Theme - professional styling
        val corporateTheme = mediator.send(
            CreateTheme(
                id = ThemeId.of("demo-corp"),
                tenantId = tenantId,
                name = "Corporate",
                description = "Professional corporate styling with clean typography",
                documentStyles = DocumentStyles(
                    fontFamily = "Helvetica",
                    fontSize = "11pt",
                    color = "#333333",
                    lineHeight = "1.5",
                ),
                blockStylePresets = mapOf(
                    "heading1" to mapOf(
                        "fontSize" to "24pt",
                        "fontWeight" to "bold",
                        "color" to "#1a1a1a",
                        "marginBottom" to "16px",
                    ),
                    "heading2" to mapOf(
                        "fontSize" to "18pt",
                        "fontWeight" to "bold",
                        "color" to "#2a2a2a",
                        "marginBottom" to "12px",
                    ),
                    "quote" to mapOf(
                        "fontStyle" to "italic",
                        "color" to "#666666",
                        "marginLeft" to "20px",
                        "borderLeft" to "3px solid #cccccc",
                        "paddingLeft" to "12px",
                    ),
                ),
            ),
        )
        log.info("Created Corporate theme")

        // Modern Theme - contemporary design
        mediator.send(
            CreateTheme(
                id = ThemeId.of("demo-modern"),
                tenantId = tenantId,
                name = "Modern",
                description = "Contemporary design with bold accents",
                documentStyles = DocumentStyles(
                    fontFamily = "Helvetica",
                    fontSize = "10pt",
                    color = "#1f2937",
                    lineHeight = "1.6",
                ),
                blockStylePresets = mapOf(
                    "heading1" to mapOf(
                        "fontSize" to "28pt",
                        "fontWeight" to "700",
                        "color" to "#111827",
                        "marginBottom" to "20px",
                    ),
                    "heading2" to mapOf(
                        "fontSize" to "20pt",
                        "fontWeight" to "600",
                        "color" to "#374151",
                        "marginBottom" to "14px",
                    ),
                    "accent" to mapOf(
                        "backgroundColor" to "#f3f4f6",
                        "padding" to "16px",
                        "borderRadius" to "8px",
                    ),
                ),
            ),
        )
        log.info("Created Modern theme")

        return corporateTheme.id
    }

    private fun createTemplateFromDefinition(tenantId: TenantId, definition: TemplateDefinition) {
        // 1. Create template with basic metadata
        val template = mediator.send(
            CreateDocumentTemplate(
                id = TemplateId.of(definition.slug),
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
        private const val DEMO_VERSION = "3.1.0" // Bump this to reset demo tenant
        private const val DEMO_VERSION_KEY = "demo_version"
        private const val DEMO_TENANT_ID = "demo-tenant"
        private const val DEMO_TENANT_NAME = "Demo Tenant"
    }
}
