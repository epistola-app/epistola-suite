package app.epistola.suite.demo

import app.epistola.suite.apikeys.ApiKey
import app.epistola.suite.apikeys.ApiKeyRepository
import app.epistola.suite.apikeys.ApiKeyService
import app.epistola.suite.catalog.CatalogClient
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.commands.InstallFromCatalog
import app.epistola.suite.catalog.commands.InstallStatus
import app.epistola.suite.catalog.commands.RegisterCatalog
import app.epistola.suite.catalog.commands.UpgradeCatalog
import app.epistola.suite.catalog.queries.GetCatalog
import app.epistola.suite.common.ids.ApiKeyKey
import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.UserKey
import app.epistola.suite.environments.commands.CreateEnvironment
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.mediator.MediatorContext
import app.epistola.suite.metadata.AppMetadataService
import app.epistola.suite.security.AuthProperties
import app.epistola.suite.security.EpistolaPrincipal
import app.epistola.suite.security.PlatformRole
import app.epistola.suite.security.SecurityContext
import app.epistola.suite.security.TenantRole
import app.epistola.suite.tenants.commands.CreateTenant
import app.epistola.suite.tenants.queries.ListTenants
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper

@Component
@ConditionalOnProperty(
    name = ["epistola.demo.enabled"],
    havingValue = "true",
    matchIfMissing = false,
)
class DemoLoader(
    private val mediator: Mediator,
    private val metadataService: AppMetadataService,
    private val catalogClient: CatalogClient,
    private val objectMapper: ObjectMapper,
    private val transactionTemplate: TransactionTemplate,
    private val apiKeyRepository: ApiKeyRepository,
    private val apiKeyService: ApiKeyService,
    private val jdbcClient: org.springframework.jdbc.core.simple.JdbcClient,
    private val authProperties: AuthProperties,
) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        try {
            SecurityContext.runWithPrincipal(SYSTEM_PRINCIPAL) {
                MediatorContext.runWithMediator(mediator) {
                    ensureDemoTenant()
                    ensureDemoCatalog()
                }
            }
        } catch (e: Exception) {
            log.error("Failed to load demo: {}", e.message, e)
        }
    }

    /**
     * Ensures the demo tenant exists. Creates it with users, API key, and environments if missing.
     */
    private fun ensureDemoTenant() {
        val tenantKey = TenantKey.of(DEMO_TENANT_ID)
        val existingTenants = mediator.query(ListTenants())
        val exists = existingTenants.any { it.id == tenantKey }

        if (exists) {
            log.info("Demo tenant already exists")
            return
        }

        transactionTemplate.executeWithoutResult {
            val tenant = mediator.send(CreateTenant(id = tenantKey, name = DEMO_TENANT_NAME))
            log.info("Created demo tenant: {} (id={})", tenant.name, tenant.id)

            seedLocalUsers(tenant.id)

            val tenantId = TenantId(tenant.id)
            createDemoApiKey(tenantId)

            mediator.send(CreateEnvironment(id = EnvironmentId(EnvironmentKey.of("staging"), tenantId), name = "Staging"))
            mediator.send(CreateEnvironment(id = EnvironmentId(EnvironmentKey.of("production"), tenantId), name = "Production"))
            log.info("Created environments: staging, production")
        }
    }

    /**
     * Ensures the demo catalog is installed and up to date.
     * Uses the catalog's release version from the manifest to detect changes.
     */
    private fun ensureDemoCatalog() {
        val tenantKey = TenantKey.of(DEMO_TENANT_ID)

        // Check if demo catalog is already installed
        val existingCatalog = mediator.query(GetCatalog(tenantKey = tenantKey, catalogKey = CatalogKey.of(DEMO_CATALOG_SLUG)))

        if (existingCatalog != null) {
            // Fetch manifest to check version
            val manifest = catalogClient.fetchManifest(DEMO_CATALOG_URL, app.epistola.suite.catalog.AuthType.NONE, null)
            val remoteVersion = manifest.release.version
            val installedVersion = existingCatalog.installedReleaseVersion

            if (remoteVersion == installedVersion) {
                log.info("Demo catalog up to date (version {})", installedVersion)
                return
            }

            log.info("Demo catalog version changed: {} -> {}", installedVersion, remoteVersion)
            transactionTemplate.executeWithoutResult {
                val result = mediator.send(UpgradeCatalog(tenantKey = tenantKey, catalogKey = existingCatalog.id))
                val installed = result.installResults.count { it.status == InstallStatus.INSTALLED }
                val updated = result.installResults.count { it.status == InstallStatus.UPDATED }
                val failed = result.installResults.count { it.status == InstallStatus.FAILED }
                log.info(
                    "Upgraded demo catalog: {} -> {}, {} installed, {} updated, {} failed, {} removed",
                    result.previousVersion,
                    result.newVersion,
                    installed,
                    updated,
                    failed,
                    result.removedResources.size,
                )
            }
            return
        }

        // First-time registration
        transactionTemplate.executeWithoutResult {
            val catalog = mediator.send(RegisterCatalog(tenantKey = tenantKey, sourceUrl = DEMO_CATALOG_URL))
            log.info("Registered demo catalog: {} (version {})", catalog.name, catalog.installedReleaseVersion)

            val results = mediator.send(InstallFromCatalog(tenantKey = tenantKey, catalogKey = catalog.id))
            val installed = results.count { it.status == InstallStatus.INSTALLED }
            val failed = results.count { it.status == InstallStatus.FAILED }
            log.info("Installed {} resources from demo catalog ({} failed)", installed, failed)
            results.filter { it.status == InstallStatus.FAILED }.forEach {
                log.warn("Failed to install {} '{}': {}", it.type, it.slug, it.errorMessage)
            }
        }
    }

    /**
     * Seeds local dev users into the database so that foreign key constraints
     * (e.g., feedback.created_by) are satisfied. These users match the in-memory
     * users defined in LocalUserDetailsService.
     *
     * Uses the same deterministic UUID generation as LocalUserDetailsService
     * so that user IDs match between database records and authentication principals.
     */
    private fun seedLocalUsers(tenantKey: TenantKey) {
        val users = authProperties.localUsers.ifEmpty {
            // Fallback defaults when no local-users are configured (demo without localauth)
            listOf(
                app.epistola.suite.security.LocalUserProperties(
                    username = "admin@local",
                    password = "unused",
                    displayName = "Local Admin",
                    tenant = tenantKey.value,
                ),
                app.epistola.suite.security.LocalUserProperties(
                    username = "user@local",
                    password = "unused",
                    displayName = "Local User",
                    tenant = tenantKey.value,
                ),
            )
        }

        for (user in users) {
            val userId = deterministicUserId(user.username)

            jdbcClient.sql(
                """
                INSERT INTO users (id, external_id, email, display_name, provider, enabled, created_at)
                VALUES (?, ?, ?, ?, 'LOCAL', true, NOW())
                ON CONFLICT (external_id, provider) DO UPDATE SET
                    id = EXCLUDED.id,
                    email = EXCLUDED.email,
                    display_name = EXCLUDED.display_name,
                    enabled = EXCLUDED.enabled
                """,
            )
                .param(userId.value)
                .param(user.username)
                .param(user.username)
                .param(user.displayName)
                .update()

            jdbcClient.sql(
                """
                INSERT INTO tenant_memberships (user_id, tenant_key)
                VALUES (?, ?)
                ON CONFLICT DO NOTHING
                """,
            )
                .param(userId.value)
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

    companion object {
        private const val DEMO_CATALOG_URL = "classpath:demo/catalog/catalog.json"
        private const val DEMO_CATALOG_SLUG = "epistola-demo"
        private const val DEMO_TENANT_ID = "demo"
        private const val DEMO_TENANT_NAME = "Demo"
        private const val DEMO_LOGO_ASSET_ID = "00000000-0000-0000-0000-100000000001"
        private const val DEMO_API_KEY_ID = "00000000-0000-0000-0000-200000000001"
        const val DEMO_API_KEY = "epk_demo_000000000000000000000000000000000000"

        /**
         * Generates a deterministic UUID from a username, matching [LocalUserDetailsService].
         */
        fun deterministicUserId(username: String): UserKey = UserKey.of(java.util.UUID.nameUUIDFromBytes(username.toByteArray(java.nio.charset.StandardCharsets.UTF_8)))

        /** Bootstrap principal used by DemoLoader — has full access to all operations. */
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
}
