package app.epistola.suite.attributes

import app.epistola.suite.CoreIntegrationTestBase
import app.epistola.suite.attributes.commands.CreateAttributeDefinition
import app.epistola.suite.attributes.commands.DeleteAttributeDefinition
import app.epistola.suite.attributes.commands.UpdateAttributeDefinition
import app.epistola.suite.attributes.queries.GetAttributeDefinition
import app.epistola.suite.attributes.queries.ListAttributeDefinitions
import app.epistola.suite.common.ids.AttributeId
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.validation.ValidationException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class AttributeDefinitionTest : CoreIntegrationTestBase() {

    @Test
    fun `create attribute definition with allowed values`() {
        val tenant = createTenant("Test Tenant")

        withMediator {
            val attr = CreateAttributeDefinition(
                id = AttributeId.of("language"),
                tenantId = tenant.id,
                displayName = "Language",
                allowedValues = listOf("dutch", "english", "german"),
            ).execute()

            assertThat(attr.id.value).isEqualTo("language")
            assertThat(attr.displayName).isEqualTo("Language")
            assertThat(attr.allowedValues).containsExactly("dutch", "english", "german")
            assertThat(attr.tenantId).isEqualTo(tenant.id)
            assertThat(attr.createdAt).isNotNull()
            assertThat(attr.lastModified).isNotNull()
        }
    }

    @Test
    fun `create attribute definition without allowed values`() {
        val tenant = createTenant("Test Tenant")

        withMediator {
            val attr = CreateAttributeDefinition(
                id = AttributeId.of("custom-field"),
                tenantId = tenant.id,
                displayName = "Custom Field",
            ).execute()

            assertThat(attr.allowedValues).isEmpty()
        }
    }

    @Test
    fun `create attribute definition rejects blank display name`() {
        val tenant = createTenant("Test Tenant")

        assertThatThrownBy {
            CreateAttributeDefinition(
                id = AttributeId.of("language"),
                tenantId = tenant.id,
                displayName = "",
            )
        }.isInstanceOf(ValidationException::class.java)
            .hasMessageContaining("Display name is required")
    }

    @Test
    fun `create attribute definition rejects duplicate allowed values`() {
        val tenant = createTenant("Test Tenant")

        assertThatThrownBy {
            CreateAttributeDefinition(
                id = AttributeId.of("language"),
                tenantId = tenant.id,
                displayName = "Language",
                allowedValues = listOf("dutch", "dutch"),
            )
        }.isInstanceOf(ValidationException::class.java)
            .hasMessageContaining("unique")
    }

    @Test
    fun `list attribute definitions returns all for tenant`() {
        val tenant = createTenant("Test Tenant")

        withMediator {
            CreateAttributeDefinition(
                id = AttributeId.of("language"),
                tenantId = tenant.id,
                displayName = "Language",
                allowedValues = listOf("dutch", "english"),
            ).execute()

            CreateAttributeDefinition(
                id = AttributeId.of("brand"),
                tenantId = tenant.id,
                displayName = "Brand",
                allowedValues = listOf("acme", "globex"),
            ).execute()

            val attributes = ListAttributeDefinitions(tenantId = tenant.id).query()

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

        withMediator {
            CreateAttributeDefinition(
                id = AttributeId.of("language"),
                tenantId = tenant1.id,
                displayName = "Language",
            ).execute()

            CreateAttributeDefinition(
                id = AttributeId.of("brand"),
                tenantId = tenant2.id,
                displayName = "Brand",
            ).execute()

            val tenant1Attrs = ListAttributeDefinitions(tenantId = tenant1.id).query()
            val tenant2Attrs = ListAttributeDefinitions(tenantId = tenant2.id).query()

            assertThat(tenant1Attrs).hasSize(1)
            assertThat(tenant1Attrs[0].id.value).isEqualTo("language")

            assertThat(tenant2Attrs).hasSize(1)
            assertThat(tenant2Attrs[0].id.value).isEqualTo("brand")
        }
    }

    @Test
    fun `get attribute definition by id`() {
        val tenant = createTenant("Test Tenant")

        withMediator {
            CreateAttributeDefinition(
                id = AttributeId.of("language"),
                tenantId = tenant.id,
                displayName = "Language",
                allowedValues = listOf("dutch", "english"),
            ).execute()

            val attr = GetAttributeDefinition(
                id = AttributeId.of("language"),
                tenantId = tenant.id,
            ).query()

            assertThat(attr).isNotNull
            assertThat(attr!!.displayName).isEqualTo("Language")
            assertThat(attr.allowedValues).containsExactly("dutch", "english")
        }
    }

    @Test
    fun `get attribute definition returns null for non-existent`() {
        val tenant = createTenant("Test Tenant")

        withMediator {
            val attr = GetAttributeDefinition(
                id = AttributeId.of("non-existent"),
                tenantId = tenant.id,
            ).query()

            assertThat(attr).isNull()
        }
    }

    @Test
    fun `update attribute definition`() {
        val tenant = createTenant("Test Tenant")

        withMediator {
            CreateAttributeDefinition(
                id = AttributeId.of("language"),
                tenantId = tenant.id,
                displayName = "Language",
                allowedValues = listOf("dutch", "english"),
            ).execute()

            val updated = UpdateAttributeDefinition(
                id = AttributeId.of("language"),
                tenantId = tenant.id,
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

        withMediator {
            val updated = UpdateAttributeDefinition(
                id = AttributeId.of("non-existent"),
                tenantId = tenant.id,
                displayName = "Nope",
            ).execute()

            assertThat(updated).isNull()
        }
    }

    @Test
    fun `delete attribute definition`() {
        val tenant = createTenant("Test Tenant")

        withMediator {
            CreateAttributeDefinition(
                id = AttributeId.of("language"),
                tenantId = tenant.id,
                displayName = "Language",
            ).execute()

            val deleted = DeleteAttributeDefinition(
                id = AttributeId.of("language"),
                tenantId = tenant.id,
            ).execute()

            assertThat(deleted).isTrue()

            val attr = GetAttributeDefinition(
                id = AttributeId.of("language"),
                tenantId = tenant.id,
            ).query()
            assertThat(attr).isNull()
        }
    }

    @Test
    fun `delete non-existent attribute definition returns false`() {
        val tenant = createTenant("Test Tenant")

        withMediator {
            val deleted = DeleteAttributeDefinition(
                id = AttributeId.of("non-existent"),
                tenantId = tenant.id,
            ).execute()

            assertThat(deleted).isFalse()
        }
    }

    @Test
    fun `same attribute id can exist in different tenants`() {
        val tenant1 = createTenant("Tenant 1")
        val tenant2 = createTenant("Tenant 2")

        withMediator {
            val attr1 = CreateAttributeDefinition(
                id = AttributeId.of("language"),
                tenantId = tenant1.id,
                displayName = "Language for T1",
                allowedValues = listOf("dutch"),
            ).execute()

            val attr2 = CreateAttributeDefinition(
                id = AttributeId.of("language"),
                tenantId = tenant2.id,
                displayName = "Language for T2",
                allowedValues = listOf("english"),
            ).execute()

            assertThat(attr1.id).isEqualTo(attr2.id)
            assertThat(attr1.displayName).isNotEqualTo(attr2.displayName)
        }
    }
}
