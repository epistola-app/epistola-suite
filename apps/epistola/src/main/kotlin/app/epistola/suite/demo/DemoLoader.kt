package app.epistola.suite.demo

import app.epistola.suite.assets.AssetMediaType
import app.epistola.suite.assets.commands.UploadAsset
import app.epistola.suite.attributes.commands.CreateAttributeDefinition
import app.epistola.suite.common.ids.AssetId
import app.epistola.suite.common.ids.AttributeId
import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.environments.commands.CreateEnvironment
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.mediator.MediatorContext
import app.epistola.suite.metadata.AppMetadataService
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.UpdateDocumentTemplate
import app.epistola.suite.templates.commands.variants.CreateVariant
import app.epistola.suite.templates.commands.variants.UpdateVariant
import app.epistola.suite.templates.commands.versions.PublishToEnvironment
import app.epistola.suite.templates.commands.versions.UpdateDraft
import app.epistola.suite.templates.queries.variants.ListVariants
import app.epistola.suite.templates.queries.versions.ListVersions
import app.epistola.suite.tenants.commands.CreateTenant
import app.epistola.suite.tenants.commands.DeleteTenant
import app.epistola.suite.tenants.queries.ListTenants
import app.epistola.suite.themes.BlockStylePreset
import app.epistola.suite.themes.BlockStylePresets
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
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

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
        MediatorContext.runWithMediator(mediator) {
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

                // Upload demo logo asset with well-known ID
                val logoBytes = generateDemoLogoPng()
                mediator.send(
                    UploadAsset(
                        id = AssetId.of(DEMO_LOGO_ASSET_ID),
                        tenantId = tenant.id,
                        name = "Epistola Logo",
                        mediaType = AssetMediaType.PNG,
                        content = logoBytes,
                        width = 120,
                        height = 40,
                    ),
                )
                log.info("Uploaded demo logo asset (id={})", DEMO_LOGO_ASSET_ID)

                // Create additional demo themes
                val corporateThemeId = createDemoThemes(tenant.id)

                // Set "Corporate" as the default theme instead of the auto-created "Tenant Default"
                if (corporateThemeId != null) {
                    mediator.send(app.epistola.suite.tenants.commands.SetTenantDefaultTheme(tenantId = tenant.id, themeId = corporateThemeId))
                    log.info("Set Corporate theme as tenant default")
                }

                // Create environments
                val staging = mediator.send(CreateEnvironment(id = EnvironmentId.of("staging"), tenantId = tenant.id, name = "Staging"))
                val production = mediator.send(CreateEnvironment(id = EnvironmentId.of("production"), tenantId = tenant.id, name = "Production"))
                log.info("Created environments: staging, production")

                // Create attribute definitions
                mediator.send(
                    CreateAttributeDefinition(
                        id = AttributeId.of("language"),
                        tenantId = tenant.id,
                        displayName = "Language",
                        allowedValues = listOf("nl", "en"),
                    ),
                )
                log.info("Created attribute definition: language (nl, en)")

                // Load and create templates from JSON definitions
                val definitions = loadTemplateDefinitions()
                log.info("Loaded {} template definitions", definitions.size)

                definitions.forEach { definition ->
                    createTemplateFromDefinition(tenant.id, definition, staging.id, production.id)
                }

                log.info("Created {} demo templates with environments and variants", definitions.size)
            }
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
                documentStyles = mapOf(
                    "fontFamily" to "Helvetica, Arial, sans-serif",
                    "fontSize" to "11pt",
                    "color" to "#333333",
                    "lineHeight" to "1.5",
                ),
                blockStylePresets = BlockStylePresets(
                    mapOf(
                        "heading1" to BlockStylePreset(
                            label = "Heading 1",
                            styles = mapOf(
                                "fontSize" to "24pt",
                                "fontWeight" to "bold",
                                "color" to "#1a1a1a",
                                "marginBottom" to "16px",
                            ),
                            applicableTo = listOf("text", "container"),
                        ),
                        "heading2" to BlockStylePreset(
                            label = "Heading 2",
                            styles = mapOf(
                                "fontSize" to "18pt",
                                "fontWeight" to "bold",
                                "color" to "#2a2a2a",
                                "marginBottom" to "12px",
                            ),
                            applicableTo = listOf("text", "container"),
                        ),
                        "quote" to BlockStylePreset(
                            label = "Quote",
                            styles = mapOf(
                                "fontStyle" to "italic",
                                "color" to "#666666",
                                "marginLeft" to "20px",
                                "borderLeft" to "3px solid #cccccc",
                                "paddingLeft" to "12px",
                            ),
                        ),
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
                documentStyles = mapOf(
                    "fontFamily" to "Inter, system-ui, sans-serif",
                    "fontSize" to "10pt",
                    "color" to "#1f2937",
                    "lineHeight" to "1.6",
                ),
                blockStylePresets = BlockStylePresets(
                    mapOf(
                        "heading1" to BlockStylePreset(
                            label = "Heading 1",
                            styles = mapOf(
                                "fontSize" to "28pt",
                                "fontWeight" to "700",
                                "color" to "#111827",
                                "marginBottom" to "20px",
                            ),
                            applicableTo = listOf("text", "container"),
                        ),
                        "heading2" to BlockStylePreset(
                            label = "Heading 2",
                            styles = mapOf(
                                "fontSize" to "20pt",
                                "fontWeight" to "600",
                                "color" to "#374151",
                                "marginBottom" to "14px",
                            ),
                            applicableTo = listOf("text", "container"),
                        ),
                        "accent" to BlockStylePreset(
                            label = "Accent Box",
                            styles = mapOf(
                                "backgroundColor" to "#f3f4f6",
                                "padding" to "16px",
                                "borderRadius" to "8px",
                            ),
                        ),
                    ),
                ),
            ),
        )
        log.info("Created Modern theme")

        return corporateTheme.id
    }

    private fun createTemplateFromDefinition(
        tenantId: TenantId,
        definition: TemplateDefinition,
        stagingId: EnvironmentId,
        productionId: EnvironmentId,
    ) {
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

        // 4. Set language attribute on default variant (Dutch)
        mediator.send(
            UpdateVariant(
                tenantId = tenantId,
                templateId = template.id,
                variantId = defaultVariant.id,
                title = defaultVariant.title,
                attributes = mapOf("language" to "nl"),
            ),
        )
        log.debug("Set default variant attributes: language=nl")

        // 5. Update the draft version with visual content
        mediator.send(
            UpdateDraft(
                tenantId = tenantId,
                templateId = template.id,
                variantId = defaultVariant.id,
                templateModel = definition.templateModel,
            ),
        )
        log.debug("Updated draft template model for: {}", template.name)

        // 6. Get the draft version ID for publishing
        val defaultVersions = mediator.query(ListVersions(tenantId = tenantId, templateId = template.id, variantId = defaultVariant.id))
        val defaultDraft = defaultVersions.first()

        // 7. Publish default variant to staging and production
        mediator.send(
            PublishToEnvironment(
                tenantId = tenantId,
                templateId = template.id,
                variantId = defaultVariant.id,
                versionId = defaultDraft.id,
                environmentId = stagingId,
            ),
        )
        mediator.send(
            PublishToEnvironment(
                tenantId = tenantId,
                templateId = template.id,
                variantId = defaultVariant.id,
                versionId = defaultDraft.id,
                environmentId = productionId,
            ),
        )
        log.debug("Published default variant to staging and production")

        // 8. Create English variant
        val englishVariantId = VariantId.of("${definition.slug}-en")
        val englishVariant = mediator.send(
            CreateVariant(
                id = englishVariantId,
                tenantId = tenantId,
                templateId = template.id,
                title = "${definition.name} (English)",
                description = "English version",
                attributes = mapOf("language" to "en"),
            ),
        ) ?: error("Failed to create English variant for template ${template.id}")

        // 9. Update English variant draft with template model
        mediator.send(
            UpdateDraft(
                tenantId = tenantId,
                templateId = template.id,
                variantId = englishVariant.id,
                templateModel = definition.templateModel,
            ),
        )

        // 10. Get English draft version and publish to staging only
        val englishVersions = mediator.query(ListVersions(tenantId = tenantId, templateId = template.id, variantId = englishVariant.id))
        val englishDraft = englishVersions.first()

        mediator.send(
            PublishToEnvironment(
                tenantId = tenantId,
                templateId = template.id,
                variantId = englishVariant.id,
                versionId = englishDraft.id,
                environmentId = stagingId,
            ),
        )
        log.debug("Published English variant to staging")
    }

    /**
     * Generates a simple PNG logo: a blue rounded rectangle with white "E" text.
     * Uses the invoice's accent color (#2563eb).
     */
    private fun generateDemoLogoPng(): ByteArray {
        val width = 120
        val height = 40
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()

        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

        // Blue rounded rectangle background
        g.color = Color(0x25, 0x63, 0xEB)
        g.fillRoundRect(0, 0, width, height, 8, 8)

        // White "E" letter
        g.color = Color.WHITE
        g.font = Font(Font.SANS_SERIF, Font.BOLD, 28)
        val fm = g.fontMetrics
        val text = "E"
        val textX = (width - fm.stringWidth(text)) / 2
        val textY = (height - fm.height) / 2 + fm.ascent
        g.drawString(text, textX, textY)

        g.dispose()

        val output = ByteArrayOutputStream()
        ImageIO.write(image, "png", output)
        return output.toByteArray()
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
        private const val DEMO_VERSION = "8.0.0" // Bump this to reset demo tenant
        private const val DEMO_VERSION_KEY = "demo_version"
        private const val DEMO_TENANT_ID = "demo-tenant"
        private const val DEMO_TENANT_NAME = "Demo Tenant"
        private const val DEMO_LOGO_ASSET_ID = "00000000-0000-0000-0000-000000000001"
    }
}
