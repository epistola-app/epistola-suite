package app.epistola.suite.demo

import app.epistola.suite.apikeys.ApiKey
import app.epistola.suite.apikeys.ApiKeyRepository
import app.epistola.suite.apikeys.ApiKeyService
import app.epistola.suite.assets.AssetMediaType
import app.epistola.suite.assets.commands.UploadAsset
import app.epistola.suite.attributes.commands.CreateAttributeDefinition
import app.epistola.suite.common.ids.ApiKeyKey
import app.epistola.suite.common.ids.AssetKey
import app.epistola.suite.common.ids.AttributeId
import app.epistola.suite.common.ids.AttributeKey
import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.common.ids.UserKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.common.ids.VersionId
import app.epistola.suite.environments.commands.CreateEnvironment
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.mediator.MediatorContext
import app.epistola.suite.metadata.AppMetadataService
import app.epistola.suite.security.EpistolaPrincipal
import app.epistola.suite.security.PlatformRole
import app.epistola.suite.security.SecurityContext
import app.epistola.suite.security.TenantRole
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
    private val apiKeyRepository: ApiKeyRepository,
    private val apiKeyService: ApiKeyService,
    private val jdbcClient: org.springframework.jdbc.core.simple.JdbcClient,
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
        SecurityContext.runWithPrincipal(SYSTEM_PRINCIPAL) {
            MediatorContext.runWithMediator(mediator) {
                transactionTemplate.executeWithoutResult {
                    // Find and delete existing demo tenant(s) (if exists)
                    val existingTenants = mediator.query(ListTenants())
                    val oldTenantId = TenantKey.of("demo-tenant")
                    val demoTenants = existingTenants.filter {
                        it.id == TenantKey.of(DEMO_TENANT_ID) ||
                            it.name == DEMO_TENANT_NAME ||
                            it.id == oldTenantId ||
                            it.name == "Demo Tenant"
                    }

                    for (tenant in demoTenants) {
                        log.info("Deleting existing demo tenant (id={})", tenant.id)
                        mediator.send(DeleteTenant(id = tenant.id))
                    }

                    // Create new demo tenant (CreateTenant now auto-creates a "Tenant Default" theme)
                    val tenant = mediator.send(CreateTenant(id = TenantKey.of(DEMO_TENANT_ID), name = DEMO_TENANT_NAME))
                    log.info("Created demo tenant: {} (id={})", tenant.name, tenant.id)
                    log.info("Tenant has default theme: {}", tenant.defaultThemeKey)

                    // Create local dev users in the database (matches LocalUserDetailsService)
                    seedLocalUsers(tenant.id)

                    // Upload demo logo asset with well-known ID
                    val logoBytes = generateDemoLogoPng()
                    val tenantId = TenantId(tenant.id)
                    mediator.send(
                        UploadAsset(
                            tenantId = tenant.id,
                            id = AssetKey.of(DEMO_LOGO_ASSET_ID),
                            name = "Epistola Logo",
                            mediaType = AssetMediaType.PNG,
                            content = logoBytes,
                            width = 120,
                            height = 40,
                        ),
                    )
                    log.info("Uploaded demo logo asset (id={})", DEMO_LOGO_ASSET_ID)

                    // Create additional demo themes
                    val corporateThemeId = createDemoThemes(tenantId)

                    // Set "Corporate" as the default theme instead of the auto-created "Tenant Default"
                    if (corporateThemeId != null) {
                        mediator.send(app.epistola.suite.tenants.commands.SetTenantDefaultTheme(tenantId = tenant.id, themeId = corporateThemeId))
                        log.info("Set Corporate theme as tenant default")
                    }

                    // Create demo API key
                    createDemoApiKey(tenantId)

                    // Create environments
                    val staging = mediator.send(CreateEnvironment(id = EnvironmentId(EnvironmentKey.of("staging"), tenantId), name = "Staging"))
                    val production = mediator.send(CreateEnvironment(id = EnvironmentId(EnvironmentKey.of("production"), tenantId), name = "Production"))
                    log.info("Created environments: staging, production")

                    // Create attribute definitions
                    mediator.send(
                        CreateAttributeDefinition(
                            id = AttributeId(AttributeKey.of("language"), tenantId),
                            displayName = "Language",
                            allowedValues = listOf("nl", "en"),
                        ),
                    )
                    log.info("Created attribute definition: language (nl, en)")

                    // Load and create templates from JSON definitions
                    val definitions = loadTemplateDefinitions()
                    log.info("Loaded {} template definitions", definitions.size)

                    definitions.forEach { definition ->
                        createTemplateFromDefinition(tenantId, definition, EnvironmentId(staging.id, tenantId), EnvironmentId(production.id, tenantId))
                    }

                    log.info("Created {} demo templates with environments and variants", definitions.size)
                }
            }
        }
    }

    /**
     * Seeds local dev users into the database so that foreign key constraints
     * (e.g., feedback.created_by) are satisfied. These users match the in-memory
     * users defined in LocalUserDetailsService.
     */
    private fun seedLocalUsers(tenantKey: TenantKey) {
        val users = listOf(
            Triple(DEMO_ADMIN_USER_ID, "admin@local", "Local Admin"),
            Triple(DEMO_MEMBER_USER_ID, "user@local", "Local User"),
        )

        for ((userId, email, displayName) in users) {
            jdbcClient.sql(
                """
                INSERT INTO users (id, external_id, email, display_name, provider, enabled, created_at)
                VALUES (?, ?, ?, ?, 'LOCAL', true, NOW())
                ON CONFLICT (external_id, provider) DO NOTHING
                """,
            )
                .param(UserKey.of(userId).value)
                .param(email)
                .param(email)
                .param(displayName)
                .update()

            jdbcClient.sql(
                """
                INSERT INTO tenant_memberships (user_id, tenant_key)
                VALUES (?, ?)
                ON CONFLICT DO NOTHING
                """,
            )
                .param(UserKey.of(userId).value)
                .param(tenantKey.value)
                .update()
        }

        log.info("Seeded {} local dev users with tenant membership", users.size)
    }

    /**
     * Creates a well-known demo API key for testing external API access.
     */
    private fun createDemoApiKey(tenantId: TenantId) {
        val keyHash = apiKeyService.hashKey(DEMO_API_KEY)
        val keyPrefix = apiKeyService.extractPrefix(DEMO_API_KEY)

        val apiKey = ApiKey(
            id = ApiKeyKey.of(DEMO_API_KEY_ID),
            tenantKey = tenantId.key,
            name = "Demo API Key",
            keyPrefix = keyPrefix,
            enabled = true,
            createdAt = java.time.Instant.now(),
            lastUsedAt = null,
            expiresAt = null,
            createdBy = null,
        )

        apiKeyRepository.insert(apiKey, keyHash)
        log.info("Created demo API key: {}", DEMO_API_KEY)
    }

    /**
     * Creates demo themes and returns the ID of the Corporate theme.
     */
    private fun createDemoThemes(tenantId: TenantId): ThemeKey? {
        // Corporate Theme - professional styling
        val corporateTheme = mediator.send(
            CreateTheme(
                id = ThemeId(ThemeKey.of("demo-corp"), tenantId),
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
                                "marginBottom" to "4sp", // 16pt
                            ),
                            applicableTo = listOf("text", "container"),
                        ),
                        "heading2" to BlockStylePreset(
                            label = "Heading 2",
                            styles = mapOf(
                                "fontSize" to "18pt",
                                "fontWeight" to "bold",
                                "color" to "#2a2a2a",
                                "marginBottom" to "3sp", // 12pt
                            ),
                            applicableTo = listOf("text", "container"),
                        ),
                        "quote" to BlockStylePreset(
                            label = "Quote",
                            styles = mapOf(
                                "fontStyle" to "italic",
                                "color" to "#666666",
                                "marginLeft" to "5sp", // 20pt
                                "borderLeft" to "2pt solid #cccccc",
                                "paddingLeft" to "3sp", // 12pt
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
                id = ThemeId(ThemeKey.of("demo-modern"), tenantId),
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
                                "marginBottom" to "5sp", // 20pt
                            ),
                            applicableTo = listOf("text", "container"),
                        ),
                        "heading2" to BlockStylePreset(
                            label = "Heading 2",
                            styles = mapOf(
                                "fontSize" to "20pt",
                                "fontWeight" to "600",
                                "color" to "#374151",
                                "marginBottom" to "4sp", // 16pt (was 14px, snapped to grid)
                            ),
                            applicableTo = listOf("text", "container"),
                        ),
                        "accent" to BlockStylePreset(
                            label = "Accent Box",
                            styles = mapOf(
                                "backgroundColor" to "#f3f4f6",
                                "paddingTop" to "4sp", // 16pt
                                "paddingRight" to "4sp",
                                "paddingBottom" to "4sp",
                                "paddingLeft" to "4sp",
                                "borderRadius" to "6pt",
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
        val templateId = TemplateId(TemplateKey.of(definition.slug), tenantId)
        val template = mediator.send(
            CreateDocumentTemplate(
                id = templateId,
                name = definition.name,
            ),
        )
        log.debug("Created template: {} (id={})", template.name, template.id)

        // 2. Update template with data model and examples
        mediator.send(
            UpdateDocumentTemplate(
                id = templateId,
                dataModel = definition.dataModel,
                dataExamples = definition.dataExamples,
                forceUpdate = true, // Skip validation warnings for demo data
            ),
        )
        log.debug("Updated template metadata for: {}", template.name)

        // 3. Get the default variant (first variant, auto-created by CreateDocumentTemplate)
        val variants = mediator.query(ListVariants(templateId = templateId))
        val defaultVariant = variants.firstOrNull()
            ?: error("No default variant found for template ${template.id}")
        val defaultVariantId = VariantId(defaultVariant.id, templateId)

        // 4. Set language attribute on default variant (Dutch)
        mediator.send(
            UpdateVariant(
                variantId = defaultVariantId,
                title = defaultVariant.title,
                attributes = mapOf("language" to "nl"),
            ),
        )
        log.debug("Set default variant attributes: language=nl")

        // 5. Update the draft version with visual content
        mediator.send(
            UpdateDraft(
                variantId = defaultVariantId,
                templateModel = definition.templateModel,
            ),
        )
        log.debug("Updated draft template model for: {}", template.name)

        // 6. Get the draft version ID for publishing
        val defaultVersions = mediator.query(ListVersions(variantId = defaultVariantId))
        val defaultDraft = defaultVersions.first()

        // 7. Publish default variant to staging and production
        mediator.send(
            PublishToEnvironment(
                versionId = VersionId(defaultDraft.id, defaultVariantId),
                environmentId = stagingId,
            ),
        )
        mediator.send(
            PublishToEnvironment(
                versionId = VersionId(defaultDraft.id, defaultVariantId),
                environmentId = productionId,
            ),
        )
        log.debug("Published default variant to staging and production")

        // 8. Create English variant
        val englishVariantKey = VariantKey.of("${definition.slug}-en")
        val englishVariantId = VariantId(englishVariantKey, templateId)
        val englishVariant = mediator.send(
            CreateVariant(
                id = englishVariantId,
                title = "${definition.name} (English)",
                description = "English version",
                attributes = mapOf("language" to "en"),
            ),
        ) ?: error("Failed to create English variant for template ${template.id}")

        // 9. Update English variant draft with template model
        mediator.send(
            UpdateDraft(
                variantId = englishVariantId,
                templateModel = definition.templateModel,
            ),
        )

        // 10. Get English draft version and publish to staging only
        val englishVersions = mediator.query(ListVersions(variantId = englishVariantId))
        val englishDraft = englishVersions.first()

        mediator.send(
            PublishToEnvironment(
                versionId = VersionId(englishDraft.id, englishVariantId),
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
        private const val DEMO_VERSION = "14.0.0" // Bump this to reset demo tenant
        private const val DEMO_VERSION_KEY = "demo_version"
        private const val DEMO_TENANT_ID = "demo"
        private const val DEMO_TENANT_NAME = "Demo"
        private const val DEMO_ADMIN_USER_ID = "00000000-0000-0000-0000-000000000001"
        private const val DEMO_MEMBER_USER_ID = "00000000-0000-0000-0000-000000000002"
        private const val DEMO_LOGO_ASSET_ID = "00000000-0000-0000-0000-100000000001"
        private const val DEMO_API_KEY_ID = "00000000-0000-0000-0000-200000000001"
        const val DEMO_API_KEY = "epk_demo_000000000000000000000000000000000000"

        /** Bootstrap principal used by DemoLoader — has full access to all operations. */
        private val SYSTEM_PRINCIPAL = EpistolaPrincipal(
            userId = UserKey.of(DEMO_ADMIN_USER_ID),
            externalId = "system",
            email = "system@epistola.app",
            displayName = "System",
            tenantMemberships = emptyMap(),
            globalRoles = TenantRole.entries.toSet(),
            platformRoles = PlatformRole.entries.toSet(),
            currentTenantId = null,
        )
    }
}
