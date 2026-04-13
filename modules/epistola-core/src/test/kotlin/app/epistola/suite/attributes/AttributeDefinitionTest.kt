package app.epistola.suite.attributes

import app.epistola.suite.attributes.commands.CreateAttributeDefinition
import app.epistola.suite.attributes.commands.DeleteAttributeDefinition
import app.epistola.suite.attributes.commands.UpdateAttributeDefinition
import app.epistola.suite.attributes.queries.GetAttributeDefinition
import app.epistola.suite.attributes.queries.ListAttributeDefinitions
import app.epistola.suite.common.ids.AttributeId
import app.epistola.suite.common.ids.AttributeKey
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.validation.ValidationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class AttributeDefinitionTest : IntegrationTestBase() {

    @Test
    fun `create attribute definition with allowed values`() {
        val tenant = createTenant("Test Tenant")
        val tenantId = TenantId(tenant.id)

        withMediator {
            val attr = CreateAttributeDefinition(
                id = AttributeId(AttributeKey.of("language"), CatalogId.default(tenantId)),
                displayName = "Language",
                allowedValues = listOf("dutch", "english", "german"),
            ).execute()

            assertThat(attr.id.value).isEqualTo("language")
            assertThat(attr.displayName).isEqualTo("Language")
            assertThat(attr.allowedValues).containsExactly("dutch", "english", "german")
            assertThat(attr.tenantKey).isEqualTo(tenant.id)
            assertThat(attr.createdAt).isNotNull()
            assertThat(attr.lastModified).isNotNull()
        }
    }

    @Test
    fun `create attribute definition without allowed values`() {
        val tenant = createTenant("Test Tenant")
        val tenantId = TenantId(tenant.id)

        withMediator {
            val attr = CreateAttributeDefinition(
                id = AttributeId(AttributeKey.of("custom-field"), CatalogId.default(tenantId)),
                displayName = "Custom Field",
            ).execute()

            assertThat(attr.allowedValues).isEmpty()
        }
    }

    @Test
    fun `create attribute definition rejects blank display name`() {
        val tenant = createTenant("Test Tenant")
        val tenantId = TenantId(tenant.id)

        assertThatThrownBy {
            CreateAttributeDefinition(
                id = AttributeId(AttributeKey.of("language"), CatalogId.default(tenantId)),
                displayName = "",
            )
        }.isInstanceOf(ValidationException::class.java)
            .hasMessageContaining("Display name is required")
    }

    @Test
    fun `create attribute definition rejects duplicate allowed values`() {
        val tenant = createTenant("Test Tenant")
        val tenantId = TenantId(tenant.id)

        assertThatThrownBy {
            CreateAttributeDefinition(
                id = AttributeId(AttributeKey.of("language"), CatalogId.default(tenantId)),
                displayName = "Language",
                allowedValues = listOf("dutch", "dutch"),
            )
        }.isInstanceOf(ValidationException::class.java)
            .hasMessageContaining("unique")
    }

    @Test
    fun `list attribute definitions returns all for tenant`() {
        val tenant = createTenant("Test Tenant")
        val tenantId = TenantId(tenant.id)

        withMediator {
            CreateAttributeDefinition(
                id = AttributeId(AttributeKey.of("language"), CatalogId.default(tenantId)),
                displayName = "Language",
                allowedValues = listOf("dutch", "english"),
            ).execute()

            CreateAttributeDefinition(
                id = AttributeId(AttributeKey.of("brand"), CatalogId.default(tenantId)),
                displayName = "Brand",
                allowedValues = listOf("acme", "globex"),
            ).execute()

            val attributes = ListAttributeDefinitions(tenantId = tenantId).query()

            assertThat(attributes).hasSize(2)
            // Ordered by display_name ASC
            assertThat(attributes[0].id.value).isEqualTo("brand")
            assertThat(attributes[1].id.value).isEqualTo("language")
        }
    }

    @Test
    fun `list attribute definitions is tenant-scoped`() {
        val tenant1 = createTenant("Tenant 1")
        val tenant2 = createTenant("Tenant 2")
        val tenantId1 = TenantId(tenant1.id)
        val tenantId2 = TenantId(tenant2.id)

        withMediator {
            CreateAttributeDefinition(
                id = AttributeId(AttributeKey.of("language"), CatalogId.default(tenantId1)),
                displayName = "Language",
            ).execute()

            CreateAttributeDefinition(
                id = AttributeId(AttributeKey.of("brand"), CatalogId.default(tenantId2)),
                displayName = "Brand",
            ).execute()

            val tenant1Attrs = ListAttributeDefinitions(tenantId = tenantId1).query()
            val tenant2Attrs = ListAttributeDefinitions(tenantId = tenantId2).query()

            assertThat(tenant1Attrs).hasSize(1)
            assertThat(tenant1Attrs[0].id.value).isEqualTo("language")

            assertThat(tenant2Attrs).hasSize(1)
            assertThat(tenant2Attrs[0].id.value).isEqualTo("brand")
        }
    }

    @Test
    fun `get attribute definition by id`() {
        val tenant = createTenant("Test Tenant")
        val tenantId = TenantId(tenant.id)

        withMediator {
            CreateAttributeDefinition(
                id = AttributeId(AttributeKey.of("language"), CatalogId.default(tenantId)),
                displayName = "Language",
                allowedValues = listOf("dutch", "english"),
            ).execute()

            val attr = GetAttributeDefinition(
                id = AttributeId(AttributeKey.of("language"), CatalogId.default(tenantId)),
            ).query()

            assertThat(attr).isNotNull
            assertThat(attr!!.displayName).isEqualTo("Language")
            assertThat(attr.allowedValues).containsExactly("dutch", "english")
        }
    }

    @Test
    fun `get attribute definition returns null for non-existent`() {
        val tenant = createTenant("Test Tenant")
        val tenantId = TenantId(tenant.id)

        withMediator {
            val attr = GetAttributeDefinition(
                id = AttributeId(AttributeKey.of("non-existent"), CatalogId.default(tenantId)),
            ).query()

            assertThat(attr).isNull()
        }
    }

    @Test
    fun `update attribute definition`() {
        val tenant = createTenant("Test Tenant")
        val tenantId = TenantId(tenant.id)

        withMediator {
            CreateAttributeDefinition(
                id = AttributeId(AttributeKey.of("language"), CatalogId.default(tenantId)),
                displayName = "Language",
                allowedValues = listOf("dutch", "english"),
            ).execute()

            val updated = UpdateAttributeDefinition(
                id = AttributeId(AttributeKey.of("language"), CatalogId.default(tenantId)),
                displayName = "Language (updated)",
                allowedValues = listOf("dutch", "english", "german"),
            ).execute()

            assertThat(updated).isNotNull
            assertThat(updated!!.displayName).isEqualTo("Language (updated)")
            assertThat(updated.allowedValues).containsExactly("dutch", "english", "german")
        }
    }

    @Test
    fun `update non-existent attribute definition returns null`() {
        val tenant = createTenant("Test Tenant")
        val tenantId = TenantId(tenant.id)

        withMediator {
            val updated = UpdateAttributeDefinition(
                id = AttributeId(AttributeKey.of("non-existent"), CatalogId.default(tenantId)),
                displayName = "Nope",
            ).execute()

            assertThat(updated).isNull()
        }
    }

    @Test
    fun `delete attribute definition`() {
        val tenant = createTenant("Test Tenant")
        val tenantId = TenantId(tenant.id)

        withMediator {
            CreateAttributeDefinition(
                id = AttributeId(AttributeKey.of("language"), CatalogId.default(tenantId)),
                displayName = "Language",
            ).execute()

            val deleted = DeleteAttributeDefinition(
                id = AttributeId(AttributeKey.of("language"), CatalogId.default(tenantId)),
            ).execute()

            assertThat(deleted).isTrue()

            val attr = GetAttributeDefinition(
                id = AttributeId(AttributeKey.of("language"), CatalogId.default(tenantId)),
            ).query()
            assertThat(attr).isNull()
        }
    }

    @Test
    fun `delete non-existent attribute definition returns false`() {
        val tenant = createTenant("Test Tenant")
        val tenantId = TenantId(tenant.id)

        withMediator {
            val deleted = DeleteAttributeDefinition(
                id = AttributeId(AttributeKey.of("non-existent"), CatalogId.default(tenantId)),
            ).execute()

            assertThat(deleted).isFalse()
        }
    }

    @Test
    fun `same attribute id can exist in different tenants`() {
        val tenant1 = createTenant("Tenant 1")
        val tenant2 = createTenant("Tenant 2")
        val tenantId1 = TenantId(tenant1.id)
        val tenantId2 = TenantId(tenant2.id)

        withMediator {
            val attr1 = CreateAttributeDefinition(
                id = AttributeId(AttributeKey.of("language"), CatalogId.default(tenantId1)),
                displayName = "Language for T1",
                allowedValues = listOf("dutch"),
            ).execute()

            val attr2 = CreateAttributeDefinition(
                id = AttributeId(AttributeKey.of("language"), CatalogId.default(tenantId2)),
                displayName = "Language for T2",
                allowedValues = listOf("english"),
            ).execute()

            assertThat(attr1.id).isEqualTo(attr2.id)
            assertThat(attr1.displayName).isNotEqualTo(attr2.displayName)
        }
    }
}
