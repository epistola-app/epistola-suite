// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.demo

import app.epistola.suite.apikeys.ApiKeyService
import app.epistola.suite.banner.SiteBanner
import app.epistola.suite.banner.SiteBannerSeverity
import app.epistola.suite.banner.commands.SeedSiteBannerIfAbsent
import app.epistola.suite.catalog.commands.EnsureSubscribedCatalog
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.EnvironmentId
import app.epistola.suite.common.ids.EnvironmentKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.environments.commands.CreateEnvironment
import app.epistola.suite.features.KnownFeatures
import app.epistola.suite.features.commands.SaveFeatureToggle
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.mediator.MediatorContext
import app.epistola.suite.quality.EffectiveQualityStatus
import app.epistola.suite.quality.QualitySeverity
import app.epistola.suite.quality.QualitySourceId
import app.epistola.suite.quality.QualitySubject
import app.epistola.suite.quality.commands.AddFindingComment
import app.epistola.suite.quality.commands.IgnoreFinding
import app.epistola.suite.quality.commands.RecordManualFinding
import app.epistola.suite.quality.commands.RunQualityChecks
import app.epistola.suite.quality.queries.ListQualityFindings
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
                    ensureDemoBanner()
                    ensureQualityDemo()
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
     * Seeds the installation-wide site banner with the demo "data may be reset"
     * warning, but only if no banner is set yet — so a platform admin can later
     * edit or clear it and the change survives restarts (see [SeedSiteBannerIfAbsent]).
     */
    private fun ensureDemoBanner() {
        val seeded = mediator.send(
            SeedSiteBannerIfAbsent(
                SiteBanner(
                    message = "You are on the demo environment — data may be reset at any time.",
                    severity = SiteBannerSeverity.WARNING,
                    enabled = true,
                ),
            ),
        )
        if (seeded) log.info("Seeded demo site banner")
    }

    /**
     * Demonstrates the quality feature on the `quality-showcase` template.
     *
     * The feature is alpha and off by default, so the demo turns it on for its own tenant — a demo
     * that ships a deliberately flawed template and no way to see what is wrong with it would be
     * demonstrating nothing.
     *
     * Seeds **both halves** of the ledger. The automated half runs the real sources through the real
     * command; the human half — a finding raised by a person, a comment, and an ignore with a reason
     * — has no source to produce it, so without seeding it the demo would show only the machine side
     * of a feature whose whole point is that the two share one ledger.
     *
     * Runs on every boot, so every step is idempotent: the toggle and the ignore are upserts, and a
     * reconciling submission converges on the same rows. The manual finding is the exception —
     * `RecordManualFinding` fingerprints randomly, precisely so two reviewers raising the same
     * concern stay two notes — so it is guarded by a check for one that already exists rather than
     * accumulating a fresh copy on every restart.
     */
    private fun ensureQualityDemo() {
        val tenantKey = TenantKey.of(DEMO_TENANT_ID)
        val templateId = TemplateId(
            TemplateKey.of(SHOWCASE_TEMPLATE_KEY),
            CatalogId(CatalogKey.of(DEMO_CATALOG_KEY), TenantId(tenantKey)),
        )
        val variantId = VariantId(VariantKey.of(SHOWCASE_VARIANT_KEY), templateId)

        try {
            mediator.send(SaveFeatureToggle(tenantKey, KnownFeatures.QUALITY, enabled = true))
            mediator.send(RunQualityChecks(variantId))

            val subject = QualitySubject.of(variantId)
            seedManualFindingIfAbsent(tenantKey, subject)
            seedIgnoreIfPresent(tenantKey)
            log.info("Seeded quality demo on {}", templateId.toUrn())
        } catch (e: Exception) {
            // The rest of the demo is worth having even if the showcase is missing (an older
            // catalog, say). Never fail the boot over it.
            log.warn("Could not seed the quality demo: {}", e.message)
        }
    }

    private fun seedManualFindingIfAbsent(
        tenantKey: TenantKey,
        subject: QualitySubject,
    ) {
        val existing = mediator.query(
            ListQualityFindings(tenantKey = tenantKey, sourceId = QualitySourceId.MANUAL, status = null),
        )
        if (existing.total > 0) return

        val key = mediator.send(
            RecordManualFinding(
                subject = subject,
                message = "The closing is abrupt — a reminder letter should say what happens next.",
                severity = QualitySeverity.WARNING,
            ),
        )
        mediator.send(
            AddFindingComment(
                tenantKey = tenantKey,
                findingKey = key,
                body = "Agreed. No check can catch this one, which is why it is here by hand.",
            ),
        )
    }

    /**
     * Ignores the overlong-text finding, with a reason.
     *
     * Keyed on the finding's fingerprint rather than its row, so it survives the block being edited
     * (as long as it stays overlong) and every later publish — the property the demo is here to
     * show. Skips silently when the finding is absent; the source may have been changed.
     */
    private fun seedIgnoreIfPresent(tenantKey: TenantKey) {
        val longText = mediator.query(
            ListQualityFindings(tenantKey = tenantKey, ruleId = LONG_TEXT_RULE_ID, status = null),
        ).items.firstOrNull() ?: return

        if (longText.effectiveStatus == EffectiveQualityStatus.IGNORED) return
        mediator.send(
            IgnoreFinding(
                tenantKey = tenantKey,
                findingKey = longText.key,
                reason = "Legal signed off on this wording — it has to stay verbatim.",
            ),
        )
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
        private const val DEMO_CATALOG_KEY = "epistola-demo"
        private const val DEMO_TENANT_ID = "demo"
        private const val DEMO_TENANT_NAME = "Demo"

        /** The deliberately-flawed template the quality demo hangs off. */
        private const val SHOWCASE_TEMPLATE_KEY = "quality-showcase"
        private const val SHOWCASE_VARIANT_KEY = "default"
        private const val LONG_TEXT_RULE_ID = "example.long-text"
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
