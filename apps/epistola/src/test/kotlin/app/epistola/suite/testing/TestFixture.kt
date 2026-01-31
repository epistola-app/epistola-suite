package app.epistola.suite.testing

import app.epistola.suite.common.UUIDv7
import app.epistola.suite.mediator.Mediator
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
import java.util.UUID

@DslMarker
annotation class TestFixtureDsl

@Component
class TestFixtureFactory(
    private val mediator: Mediator,
) {
    fun <T> fixture(block: TestFixture.() -> T): T {
        val fixture = TestFixture(mediator)
        return try {
            fixture.block()
        } finally {
            fixture.cleanup()
        }
    }
}

@TestFixtureDsl
class TestFixture(
    private val mediator: Mediator,
) {
    private val createdTenants = mutableListOf<UUID>()
    private var givenContext: GivenContext? = null
    private var result: Any? = null

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
            mediator.send(DeleteTenant(tenantId))
        }
        createdTenants.clear()
    }

    fun deleteAllTenants() {
        mediator.query(ListTenants()).forEach { tenant ->
            mediator.send(DeleteTenant(tenant.id))
        }
        createdTenants.clear()
    }

    @TestFixtureDsl
    inner class GivenContext {
        fun tenant(name: String): Tenant {
            val tenant = this@TestFixture.mediator.send(CreateTenant(id = UUIDv7.generate(), name = name))
            this@TestFixture.createdTenants.add(tenant.id)
            return tenant
        }

        fun template(
            tenant: Tenant,
            name: String,
        ): DocumentTemplate = this@TestFixture.mediator.send(
            CreateDocumentTemplate(
                id = UUIDv7.generate(),
                tenantId = tenant.id,
                name = name,
            ),
        )

        fun variant(
            tenant: Tenant,
            template: DocumentTemplate,
            title: String? = null,
            tags: Map<String, String> = emptyMap(),
        ): TemplateVariant = this@TestFixture.mediator.send(
            CreateVariant(
                id = UUIDv7.generate(),
                tenantId = tenant.id,
                templateId = template.id,
                title = title,
                description = null,
                tags = tags,
            ),
        )!!

        fun noTenants() {
            this@TestFixture.deleteAllTenants()
        }
    }

    @TestFixtureDsl
    inner class WhenContext {
        fun createTenant(name: String): Tenant {
            val tenant = this@TestFixture.mediator.send(CreateTenant(id = UUIDv7.generate(), name = name))
            this@TestFixture.createdTenants.add(tenant.id)
            return tenant
        }

        fun deleteTenant(id: UUID): Boolean = this@TestFixture.mediator.send(DeleteTenant(id))

        fun listTemplates(tenant: Tenant): List<DocumentTemplate> = this@TestFixture.mediator.query(ListDocumentTemplates(tenant.id))

        fun listTenants(searchTerm: String? = null): List<Tenant> = this@TestFixture.mediator.query(ListTenants(searchTerm))
    }

    @TestFixtureDsl
    inner class ThenContext {
        @Suppress("UNCHECKED_CAST")
        fun <T> result(): T = this@TestFixture.result as T
    }
}
