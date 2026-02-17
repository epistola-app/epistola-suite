package app.epistola.suite.templates.services

import app.epistola.suite.CoreIntegrationTestBase
import app.epistola.suite.attributes.commands.CreateAttributeDefinition
import app.epistola.suite.common.TestIdHelpers
import app.epistola.suite.common.ids.AttributeId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.variants.CreateVariant
import app.epistola.suite.templates.commands.variants.SetDefaultVariant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

class VariantResolverTest : CoreIntegrationTestBase() {

    @Autowired
    private lateinit var variantResolver: VariantResolver

    private fun setupAttributeDefinitions(tenantId: TenantId): Unit = withMediator {
        mediator.send(
            CreateAttributeDefinition(
                id = AttributeId.of("language"),
                tenantId = tenantId,
                displayName = "Language",
                allowedValues = listOf("dutch", "english", "french"),
            ),
        )
        mediator.send(
            CreateAttributeDefinition(
                id = AttributeId.of("brand"),
                tenantId = tenantId,
                displayName = "Brand",
                allowedValues = listOf("acme", "globex"),
            ),
        )
    }

    @Nested
    inner class RequiredAttributeMatching {

        @Test
        fun `selects variant matching all required attributes`() {
            withMediator {
                val tenant = createTenant("Test Tenant")
                setupAttributeDefinitions(tenant.id)
                val template = mediator.send(
                    CreateDocumentTemplate(id = TestIdHelpers.nextTemplateId(), tenantId = tenant.id, name = "Invoice"),
                )

                val dutchVariant = mediator.send(
                    CreateVariant(
                        id = TestIdHelpers.nextVariantId(),
                        tenantId = tenant.id,
                        templateId = template.id,
                        title = "Dutch",
                        description = null,
                        attributes = mapOf("language" to "dutch"),
                    ),
                )!!

                mediator.send(
                    CreateVariant(
                        id = TestIdHelpers.nextVariantId(),
                        tenantId = tenant.id,
                        templateId = template.id,
                        title = "English",
                        description = null,
                        attributes = mapOf("language" to "english"),
                    ),
                )

                val resolved = variantResolver.resolve(
                    tenantId = tenant.id,
                    templateId = template.id,
                    criteria = VariantSelectionCriteria(
                        requiredAttributes = mapOf("language" to "dutch"),
                    ),
                )

                assertThat(resolved).isEqualTo(dutchVariant.id)
            }
        }

        @Test
        fun `selects variant matching multiple required attributes`() {
            withMediator {
                val tenant = createTenant("Test Tenant")
                setupAttributeDefinitions(tenant.id)
                val template = mediator.send(
                    CreateDocumentTemplate(id = TestIdHelpers.nextTemplateId(), tenantId = tenant.id, name = "Invoice"),
                )

                mediator.send(
                    CreateVariant(
                        id = TestIdHelpers.nextVariantId(),
                        tenantId = tenant.id,
                        templateId = template.id,
                        title = "Dutch",
                        description = null,
                        attributes = mapOf("language" to "dutch"),
                    ),
                )

                val dutchAcme = mediator.send(
                    CreateVariant(
                        id = TestIdHelpers.nextVariantId(),
                        tenantId = tenant.id,
                        templateId = template.id,
                        title = "Dutch Acme",
                        description = null,
                        attributes = mapOf("language" to "dutch", "brand" to "acme"),
                    ),
                )!!

                val resolved = variantResolver.resolve(
                    tenantId = tenant.id,
                    templateId = template.id,
                    criteria = VariantSelectionCriteria(
                        requiredAttributes = mapOf("language" to "dutch", "brand" to "acme"),
                    ),
                )

                assertThat(resolved).isEqualTo(dutchAcme.id)
            }
        }
    }

    @Nested
    inner class OptionalAttributeScoring {

        @Test
        fun `prefers variant with more optional matches`() {
            withMediator {
                val tenant = createTenant("Test Tenant")
                setupAttributeDefinitions(tenant.id)
                val template = mediator.send(
                    CreateDocumentTemplate(id = TestIdHelpers.nextTemplateId(), tenantId = tenant.id, name = "Invoice"),
                )

                mediator.send(
                    CreateVariant(
                        id = TestIdHelpers.nextVariantId(),
                        tenantId = tenant.id,
                        templateId = template.id,
                        title = "Dutch",
                        description = null,
                        attributes = mapOf("language" to "dutch"),
                    ),
                )

                val dutchAcme = mediator.send(
                    CreateVariant(
                        id = TestIdHelpers.nextVariantId(),
                        tenantId = tenant.id,
                        templateId = template.id,
                        title = "Dutch Acme",
                        description = null,
                        attributes = mapOf("language" to "dutch", "brand" to "acme"),
                    ),
                )!!

                val resolved = variantResolver.resolve(
                    tenantId = tenant.id,
                    templateId = template.id,
                    criteria = VariantSelectionCriteria(
                        optionalAttributes = mapOf("language" to "dutch", "brand" to "acme"),
                    ),
                )

                // dutchAcme: optionalMatches=2, totalAttrs=2 -> score = 2*10 + 2 = 22
                // dutch: optionalMatches=1, totalAttrs=1 -> score = 1*10 + 1 = 11
                assertThat(resolved).isEqualTo(dutchAcme.id)
            }
        }

        @Test
        fun `most specific variant wins via total attributes tiebreaker`() {
            withMediator {
                val tenant = createTenant("Test Tenant")
                setupAttributeDefinitions(tenant.id)
                val template = mediator.send(
                    CreateDocumentTemplate(id = TestIdHelpers.nextTemplateId(), tenantId = tenant.id, name = "Invoice"),
                )

                mediator.send(
                    CreateVariant(
                        id = TestIdHelpers.nextVariantId(),
                        tenantId = tenant.id,
                        templateId = template.id,
                        title = "Dutch",
                        description = null,
                        attributes = mapOf("language" to "dutch"),
                    ),
                )

                val dutchAcme = mediator.send(
                    CreateVariant(
                        id = TestIdHelpers.nextVariantId(),
                        tenantId = tenant.id,
                        templateId = template.id,
                        title = "Dutch Acme",
                        description = null,
                        attributes = mapOf("language" to "dutch", "brand" to "acme"),
                    ),
                )!!

                val resolved = variantResolver.resolve(
                    tenantId = tenant.id,
                    templateId = template.id,
                    criteria = VariantSelectionCriteria(
                        requiredAttributes = mapOf("language" to "dutch"),
                        optionalAttributes = mapOf("brand" to "acme"),
                    ),
                )

                assertThat(resolved).isEqualTo(dutchAcme.id)
            }
        }
    }

    @Nested
    inner class DefaultVariantFallback {

        @Test
        fun `falls back to default variant when no required match`() {
            withMediator {
                val tenant = createTenant("Test Tenant")
                setupAttributeDefinitions(tenant.id)
                val template = mediator.send(
                    CreateDocumentTemplate(id = TestIdHelpers.nextTemplateId(), tenantId = tenant.id, name = "Invoice"),
                )

                val defaultVariantId = VariantId.of("${template.id}-default")

                mediator.send(
                    CreateVariant(
                        id = TestIdHelpers.nextVariantId(),
                        tenantId = tenant.id,
                        templateId = template.id,
                        title = "French",
                        description = null,
                        attributes = mapOf("language" to "french"),
                    ),
                )

                val resolved = variantResolver.resolve(
                    tenantId = tenant.id,
                    templateId = template.id,
                    criteria = VariantSelectionCriteria(
                        requiredAttributes = mapOf("language" to "dutch"),
                    ),
                )

                assertThat(resolved).isEqualTo(defaultVariantId)
            }
        }

        @Test
        fun `default variant with attributes still acts as fallback`() {
            withMediator {
                val tenant = createTenant("Test Tenant")
                setupAttributeDefinitions(tenant.id)
                val template = mediator.send(
                    CreateDocumentTemplate(id = TestIdHelpers.nextTemplateId(), tenantId = tenant.id, name = "Invoice"),
                )

                // Give the auto-created default variant some attributes
                val defaultVariantId = VariantId.of("${template.id}-default")
                mediator.send(
                    SetDefaultVariant(tenantId = tenant.id, templateId = template.id, variantId = defaultVariantId),
                )

                // Create a non-default variant with different attributes
                mediator.send(
                    CreateVariant(
                        id = TestIdHelpers.nextVariantId(),
                        tenantId = tenant.id,
                        templateId = template.id,
                        title = "French",
                        description = null,
                        attributes = mapOf("language" to "french"),
                    ),
                )

                // Request dutch which no variant has — should fall back to default (even though it has empty attributes)
                val resolved = variantResolver.resolve(
                    tenantId = tenant.id,
                    templateId = template.id,
                    criteria = VariantSelectionCriteria(
                        requiredAttributes = mapOf("language" to "dutch"),
                    ),
                )

                assertThat(resolved).isEqualTo(defaultVariantId)
            }
        }
    }

    @Nested
    inner class AmbiguousResolution {

        @Test
        fun `throws AmbiguousVariantResolutionException when variants tie`() {
            withMediator {
                val tenant = createTenant("Test Tenant")
                setupAttributeDefinitions(tenant.id)
                val template = mediator.send(
                    CreateDocumentTemplate(id = TestIdHelpers.nextTemplateId(), tenantId = tenant.id, name = "Invoice"),
                )

                val variant1 = mediator.send(
                    CreateVariant(
                        id = TestIdHelpers.nextVariantId(),
                        tenantId = tenant.id,
                        templateId = template.id,
                        title = "Dutch",
                        description = null,
                        attributes = mapOf("language" to "dutch"),
                    ),
                )!!

                val variant2 = mediator.send(
                    CreateVariant(
                        id = TestIdHelpers.nextVariantId(),
                        tenantId = tenant.id,
                        templateId = template.id,
                        title = "Also Dutch",
                        description = null,
                        attributes = mapOf("language" to "dutch"),
                    ),
                )!!

                assertThatThrownBy {
                    variantResolver.resolve(
                        tenantId = tenant.id,
                        templateId = template.id,
                        criteria = VariantSelectionCriteria(
                            requiredAttributes = mapOf("language" to "dutch"),
                        ),
                    )
                }.isInstanceOf(AmbiguousVariantResolutionException::class.java)
                    .extracting("tiedVariantIds")
                    .asList()
                    .containsExactlyInAnyOrder(variant1.id, variant2.id)
            }
        }
    }

    @Nested
    inner class CombinedRequiredAndOptional {

        @Test
        fun `required filters then optional scores`() {
            withMediator {
                val tenant = createTenant("Test Tenant")
                setupAttributeDefinitions(tenant.id)
                val template = mediator.send(
                    CreateDocumentTemplate(id = TestIdHelpers.nextTemplateId(), tenantId = tenant.id, name = "Invoice"),
                )

                // English variant - won't pass required filter
                mediator.send(
                    CreateVariant(
                        id = TestIdHelpers.nextVariantId(),
                        tenantId = tenant.id,
                        templateId = template.id,
                        title = "English Acme",
                        description = null,
                        attributes = mapOf("language" to "english", "brand" to "acme"),
                    ),
                )

                // Dutch variant - passes required, no optional match
                mediator.send(
                    CreateVariant(
                        id = TestIdHelpers.nextVariantId(),
                        tenantId = tenant.id,
                        templateId = template.id,
                        title = "Dutch",
                        description = null,
                        attributes = mapOf("language" to "dutch"),
                    ),
                )

                // Dutch Acme variant - passes required, matches optional
                val dutchAcme = mediator.send(
                    CreateVariant(
                        id = TestIdHelpers.nextVariantId(),
                        tenantId = tenant.id,
                        templateId = template.id,
                        title = "Dutch Acme",
                        description = null,
                        attributes = mapOf("language" to "dutch", "brand" to "acme"),
                    ),
                )!!

                val resolved = variantResolver.resolve(
                    tenantId = tenant.id,
                    templateId = template.id,
                    criteria = VariantSelectionCriteria(
                        requiredAttributes = mapOf("language" to "dutch"),
                        optionalAttributes = mapOf("brand" to "acme"),
                    ),
                )

                assertThat(resolved).isEqualTo(dutchAcme.id)
            }
        }

        @Test
        fun `only optional attributes with scoring-based selection`() {
            withMediator {
                val tenant = createTenant("Test Tenant")
                setupAttributeDefinitions(tenant.id)
                val template = mediator.send(
                    CreateDocumentTemplate(id = TestIdHelpers.nextTemplateId(), tenantId = tenant.id, name = "Invoice"),
                )

                // Variant with language=dutch - score: 1*10 + 1 = 11
                mediator.send(
                    CreateVariant(
                        id = TestIdHelpers.nextVariantId(),
                        tenantId = tenant.id,
                        templateId = template.id,
                        title = "Dutch",
                        description = null,
                        attributes = mapOf("language" to "dutch"),
                    ),
                )

                // Variant with language=dutch, brand=acme - score: 2*10 + 2 = 22
                val dutchAcme = mediator.send(
                    CreateVariant(
                        id = TestIdHelpers.nextVariantId(),
                        tenantId = tenant.id,
                        templateId = template.id,
                        title = "Dutch Acme",
                        description = null,
                        attributes = mapOf("language" to "dutch", "brand" to "acme"),
                    ),
                )!!

                val resolved = variantResolver.resolve(
                    tenantId = tenant.id,
                    templateId = template.id,
                    criteria = VariantSelectionCriteria(
                        optionalAttributes = mapOf("language" to "dutch", "brand" to "acme"),
                    ),
                )

                assertThat(resolved).isEqualTo(dutchAcme.id)
            }
        }
    }

    @Nested
    inner class EdgeCases {

        @Test
        fun `empty criteria selects default variant`() {
            withMediator {
                val tenant = createTenant("Test Tenant")
                val template = mediator.send(
                    CreateDocumentTemplate(id = TestIdHelpers.nextTemplateId(), tenantId = tenant.id, name = "Invoice"),
                )

                val defaultVariantId = VariantId.of("${template.id}-default")

                val resolved = variantResolver.resolve(
                    tenantId = tenant.id,
                    templateId = template.id,
                    criteria = VariantSelectionCriteria(),
                )

                assertThat(resolved).isEqualTo(defaultVariantId)
            }
        }

        @Test
        fun `variant with extra attributes still matches required subset`() {
            withMediator {
                val tenant = createTenant("Test Tenant")
                setupAttributeDefinitions(tenant.id)
                val template = mediator.send(
                    CreateDocumentTemplate(id = TestIdHelpers.nextTemplateId(), tenantId = tenant.id, name = "Invoice"),
                )

                val dutchAcme = mediator.send(
                    CreateVariant(
                        id = TestIdHelpers.nextVariantId(),
                        tenantId = tenant.id,
                        templateId = template.id,
                        title = "Dutch Acme",
                        description = null,
                        attributes = mapOf("language" to "dutch", "brand" to "acme"),
                    ),
                )!!

                val resolved = variantResolver.resolve(
                    tenantId = tenant.id,
                    templateId = template.id,
                    criteria = VariantSelectionCriteria(
                        requiredAttributes = mapOf("language" to "dutch"),
                    ),
                )

                assertThat(resolved).isEqualTo(dutchAcme.id)
            }
        }
    }
}
