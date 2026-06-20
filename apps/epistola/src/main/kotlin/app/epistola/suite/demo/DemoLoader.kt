package app.epistola.suite.demo

import app.epistola.suite.apikeys.ApiKeyService
import app.epistola.suite.catalog.commands.EnsureSubscribedCatalog
import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.environments.commands.CreateEnvironment
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.mediator.MediatorContext
import app.epistola.suite.security.EpistolaPrincipal
import app.epistola.suite.security.PlatformRole
import app.epistola.suite.security.SecurityContext
import app.epistola.suite.security.SystemUser
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
    private val objectMapper: ObjectMapper,
    private val transactionTemplate: TransactionTemplate,
    private val apiKeyService: ApiKeyService,
    private val jdbcClient: org.springframework.jdbc.core.simple.JdbcClient,
) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        try {
            // [SystemUser] is seeded as a real users row by the core_users
            // baseline migration, so the audit FKs are already satisfiable here.
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
            // Still (re-)assert the demo API key on every boot so its scope self-heals
            // across upgrades — e.g. the api_key roles migration backfilled it to
            // CONTENT_VIEWER; this restores the full "everything key" scope. The upsert is
            // idempotent, so re-running it for an existing tenant is safe.
            ensureDemoApiKey(TenantId(tenantKey))
            return
        }

        transactionTemplate.executeWithoutResult {
            val tenant = mediator.send(CreateTenant(id = tenantKey, name = DEMO_TENANT_NAME))
            log.info("Created demo tenant: {} (id={})", tenant.name, tenant.id)

            val tenantId = TenantId(tenant.id)
            ensureDemoApiKey(tenantId)

            mediator.send(CreateEnvironment(id = EnvironmentId(EnvironmentKey.of("staging"), tenantId), name = "Staging"))
            mediator.send(CreateEnvironment(id = EnvironmentId(EnvironmentKey.of("production"), tenantId), name = "Production"))
            log.info("Created environments: staging, production")
        }
    }

    /**
     * Ensures the demo catalog is installed and up to date via the shared
     * [EnsureSubscribedCatalog] state machine (register+install / no-op /
     * upgrade by content fingerprint) — same path the system catalog uses.
     */
    private fun ensureDemoCatalog() {
        val tenantKey = TenantKey.of(DEMO_TENANT_ID)
        transactionTemplate.executeWithoutResult {
            val result = mediator.send(EnsureSubscribedCatalog(tenantKey = tenantKey, sourceUrl = DEMO_CATALOG_URL))
            log.info(
                "Demo catalog {} ({}): {} -> {}",
                result.catalogKey.value,
                result.status,
                result.previousVersion,
                result.newVersion,
            )
        }
    }

    /**
     * Ensures the well-known demo API key exists for testing external API access, with the full
     * ("everything") role scope. Idempotent: re-asserts the scope on conflict so it self-heals
     * across upgrades, and is safe to call on every boot.
     */
    private fun ensureDemoApiKey(tenantId: TenantId) {
        // Demo seeding uses a deterministic key + ID so devs can hard-code the key
        // in local config across restarts. The CQRS CreateApiKey command always
        // generates a random key, so we INSERT directly here instead.
        val keyHash = apiKeyService.hashKey(DEMO_API_KEY)
        val keyPrefix = apiKeyService.extractPrefix(DEMO_API_KEY)

        // The demo key is the convenient "everything" key for local dev / MCP, so grant all roles
        // (real keys are scoped at creation). Enum names are safe identifiers — no injection risk.
        // On conflict we re-assert the full scope so a pre-existing demo row (e.g. backfilled to the
        // migration's CONTENT_VIEWER default) self-heals back to all roles.
        val rolesLiteral = TenantRole.entries.joinToString(",") { "'${it.name}'" }
        jdbcClient.sql(
            """
            INSERT INTO api_keys (id, tenant_key, name, key_hash, key_prefix, enabled, created_at, roles)
            VALUES (?, ?, ?, ?, ?, true, NOW(), ARRAY[$rolesLiteral]::varchar[])
            ON CONFLICT (id) DO UPDATE SET roles = ARRAY[$rolesLiteral]::varchar[]
            """,
        )
            .param(java.util.UUID.fromString(DEMO_API_KEY_ID))
            .param(tenantId.key.value)
            .param("Demo API Key")
            .param(keyHash)
            .param(keyPrefix)
            .update()
        log.info("Created demo API key: {}", DEMO_API_KEY)
    }

    companion object {
        private const val DEMO_CATALOG_URL = "classpath:epistola/catalogs/demo/catalog.json"
        private const val DEMO_TENANT_ID = "demo"
        private const val DEMO_TENANT_NAME = "Demo"
        private const val DEMO_LOGO_ASSET_ID = "00000000-0000-0000-0000-100000000001"
        private const val DEMO_API_KEY_ID = "00000000-0000-0000-0000-200000000001"
        const val DEMO_API_KEY = "epk_demo_000000000000000000000000000000000000"

        /** Bootstrap principal used by DemoLoader — has full access to all operations. */
        private val SYSTEM_PRINCIPAL = EpistolaPrincipal(
            userId = SystemUser.ID,
            externalId = SystemUser.EXTERNAL_ID,
            email = SystemUser.EMAIL,
            displayName = SystemUser.DISPLAY_NAME,
            tenantMemberships = emptyMap(),
            globalRoles = TenantRole.entries.toSet(),
            platformRoles = PlatformRole.entries.toSet(),
            currentTenantId = null,
        )
    }
}
