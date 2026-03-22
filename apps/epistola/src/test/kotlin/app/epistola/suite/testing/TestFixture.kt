package app.epistola.suite.testing

import app.epistola.suite.common.TestIdHelpers
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.UserKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.mediator.MediatorContext
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.security.EpistolaPrincipal
import app.epistola.suite.security.PlatformRole
import app.epistola.suite.security.SecurityContext
import app.epistola.suite.security.TenantRole
import app.epistola.suite.templates.DocumentTemplate
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.variants.CreateVariant
import app.epistola.suite.templates.model.TemplateVariant
import app.epistola.suite.templates.queries.ListDocumentTemplates
import app.epistola.suite.tenants.Tenant
import app.epistola.suite.tenants.commands.CreateTenant
import app.epistola.suite.tenants.commands.DeleteTenant
import app.epistola.suite.tenants.queries.ListTenants
import org.springframework.stereotype.Component

@DslMarker
annotation class TestFixtureDsl

@Component
class TestFixtureFactory(
    private val mediator: Mediator,
) {
    private val testUser = EpistolaPrincipal(
        userId = UserKey.of("00000000-0000-0000-0000-000000000099"),
        externalId = "test-user",
        email = "test@example.com",
        displayName = "Test User",
        tenantMemberships = emptyMap(),
        globalRoles = TenantRole.entries.toSet(),
        platformRoles = setOf(PlatformRole.TENANT_MANAGER),
        currentTenantId = null,
    )

    fun <T> fixture(
        namespace: String,
        block: TestFixture.() -> T,
    ): T = MediatorContext.runWithMediator(mediator) {
        SecurityContext.runWithPrincipal(testUser) {
            val fixture = TestFixture(namespace)
            try {
                fixture.block()
            } finally {
                fixture.cleanup()
            }
        }
    }

    fun <T> withMediator(block: () -> T): T = MediatorContext.runWithMediator(mediator) {
        SecurityContext.runWithPrincipal(testUser) {
            block()
        }
    }
}

@TestFixtureDsl
class TestFixture(private val namespace: String) {
    private val createdTenants = mutableListOf<TenantKey>()
    private var givenContext: GivenContext? = null
    private var result: Any? = null
    private var tenantCounter = 0

    private fun nextTenantSlug(): String = "$namespace-${++tenantCounter}"

    fun given(block: GivenContext.() -> Unit): TestFixture {
        givenContext = GivenContext().apply(block)
        return this
    }

    fun <T> whenever(block: WhenContext.() -> T): TestFixture {
        val context = WhenContext()
        result = context.block()
        return this
    }

    fun then(block: ThenContext.() -> Unit) {
        ThenContext().block()
    }

    fun cleanup() {
        createdTenants.forEach { tenantId ->
            DeleteTenant(tenantId).execute()
        }
        createdTenants.clear()
    }

    fun deleteAllTenants() {
        ListTenants().query().forEach { tenant ->
            DeleteTenant(tenant.id).execute()
        }
        createdTenants.clear()
    }

    @TestFixtureDsl
    inner class GivenContext {
        fun tenant(name: String): Tenant {
            val tenant = CreateTenant(id = TenantKey.of(this@TestFixture.nextTenantSlug()), name = name).execute()
            this@TestFixture.createdTenants.add(tenant.id)
            return tenant
        }

        fun template(
            tenant: Tenant,
            name: String,
        ): DocumentTemplate = CreateDocumentTemplate(
            id = TemplateId(TestIdHelpers.nextTemplateId(), TenantId(tenant.id)),
            name = name,
        ).execute()

        fun variant(
            tenant: Tenant,
            template: DocumentTemplate,
            title: String? = null,
            attributes: Map<String, String> = emptyMap(),
        ): TemplateVariant = CreateVariant(
            id = VariantId(TestIdHelpers.nextVariantId(), TemplateId(template.id, TenantId(tenant.id))),
            title = title,
            description = null,
            attributes = attributes,
        ).execute()!!

        fun noTenants() {
            this@TestFixture.deleteAllTenants()
        }
    }

    @TestFixtureDsl
    inner class WhenContext {
        fun createTenant(name: String): Tenant {
            val tenant = CreateTenant(id = TenantKey.of(this@TestFixture.nextTenantSlug()), name = name).execute()
            this@TestFixture.createdTenants.add(tenant.id)
            return tenant
        }

        fun deleteTenant(id: TenantKey): Boolean = DeleteTenant(id).execute()

        fun listTemplates(tenant: Tenant): List<DocumentTemplate> = ListDocumentTemplates(TenantId(tenant.id)).query()

        fun listTenants(searchTerm: String? = null): List<Tenant> = ListTenants(searchTerm).query()
    }

    @TestFixtureDsl
    inner class ThenContext {
        @Suppress("UNCHECKED_CAST")
        fun <T> result(): T = this@TestFixture.result as T
    }
}
