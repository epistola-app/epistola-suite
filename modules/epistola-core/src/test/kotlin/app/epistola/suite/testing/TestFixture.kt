package app.epistola.suite.testing

import app.epistola.suite.common.TestIdHelpers
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.UserId
import app.epistola.suite.mediator.Mediator
import app.epistola.suite.mediator.MediatorContext
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.security.EpistolaPrincipal
import app.epistola.suite.security.SecurityContext
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
    /**
     * Test user for authenticated operations.
     */
    private val testUser = EpistolaPrincipal(
        userId = UserId.of("00000000-0000-0000-0000-000000000099"),
        externalId = "test-user",
        email = "test@example.com",
        displayName = "Test User",
        tenantMemberships = emptySet(),
        currentTenantId = null,
    )

    fun <T> fixture(block: TestFixture.() -> T): T = MediatorContext.runWithMediator(mediator) {
        SecurityContext.runWithPrincipal(testUser) {
            val fixture = TestFixture()
            try {
                fixture.block()
            } finally {
                fixture.cleanup()
            }
        }
    }

    /**
     * Runs the given block with the mediator and security context bound.
     * This enables use of Command.execute() and Query.query() extension functions in tests.
     *
     * Usage:
     * ```kotlin
     * withMediator {
     *     val tenant = CreateTenant("name").execute()
     *     val tenants = ListTenants().query()
     * }
     * ```
     */
    fun <T> withMediator(block: () -> T): T = MediatorContext.runWithMediator(mediator) {
        SecurityContext.runWithPrincipal(testUser) {
            block()
        }
    }
}

@TestFixtureDsl
class TestFixture {
    private val createdTenants = mutableListOf<TenantId>()
    private var givenContext: GivenContext? = null
    private var result: Any? = null
    private var tenantCounter = 0

    private fun nextTenantSlug(): String = "test-tenant-${++tenantCounter}"

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
            val tenant = CreateTenant(id = TenantId.of(this@TestFixture.nextTenantSlug()), name = name).execute()
            this@TestFixture.createdTenants.add(tenant.id)
            return tenant
        }

        fun template(
            tenant: Tenant,
            name: String,
        ): DocumentTemplate = CreateDocumentTemplate(
            id = TestIdHelpers.nextTemplateId(),
            tenantId = tenant.id,
            name = name,
        ).execute()

        fun variant(
            tenant: Tenant,
            template: DocumentTemplate,
            title: String? = null,
            tags: Map<String, String> = emptyMap(),
        ): TemplateVariant = CreateVariant(
            id = TestIdHelpers.nextVariantId(),
            tenantId = tenant.id,
            templateId = template.id,
            title = title,
            description = null,
            tags = tags,
        ).execute()!!

        fun noTenants() {
            this@TestFixture.deleteAllTenants()
        }
    }

    @TestFixtureDsl
    inner class WhenContext {
        fun createTenant(name: String): Tenant {
            val tenant = CreateTenant(id = TenantId.of(this@TestFixture.nextTenantSlug()), name = name).execute()
            this@TestFixture.createdTenants.add(tenant.id)
            return tenant
        }

        fun deleteTenant(id: TenantId): Boolean = DeleteTenant(id).execute()

        fun listTemplates(tenant: Tenant): List<DocumentTemplate> = ListDocumentTemplates(tenant.id).query()

        fun listTenants(searchTerm: String? = null): List<Tenant> = ListTenants(searchTerm).query()
    }

    @TestFixtureDsl
    inner class ThenContext {
        @Suppress("UNCHECKED_CAST")
        fun <T> result(): T = this@TestFixture.result as T
    }
}
