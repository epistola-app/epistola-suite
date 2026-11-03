// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.templates.services

import app.epistola.suite.attributes.commands.CreateAttributeDefinition
import app.epistola.suite.common.ids.AttributeId
import app.epistola.suite.common.ids.AttributeKey
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.variants.CreateVariant
import app.epistola.suite.templates.commands.variants.SetDefaultVariant
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestIdHelpers
import app.epistola.suite.testing.default
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired

class VariantResolverTest : IntegrationTestBase() {

    @Autowired
    private lateinit var variantResolver: VariantResolver

    private fun setupAttributeDefinitions(tenantId: TenantId): Unit = withMediator {
        mediator.send(
            CreateAttributeDefinition(
                id = AttributeId(AttributeKey.of("lang"), CatalogId.default(tenantId)),
                displayName = "Language",
                allowedValues = listOf("dutch", "english", "french"),
                catalogVersion = null,
            ),
        )
        mediator.send(
            CreateAttributeDefinition(
                id = AttributeId(AttributeKey.of("brand"), CatalogId.default(tenantId)),
                displayName = "Brand",
                allowedValues = listOf("acme", "globex"),
                catalogVersion = null,
            ),
        )
    }

    @Nested
    inner class RequiredAttributeMatching {

        @Test
        fun `selects variant matching all required attributes`() {
            withMediator {
                val tenant = createTenant("Test Tenant")
                val tenantId = TenantId(tenant.id)
                setupAttributeDefinitions(tenantId)
                val templateId = TemplateId(TestIdHelpers.nextTemplateId(), CatalogId.default(tenantId))
                val template = mediator.send(
                    CreateDocumentTemplate(id = templateId, name = "Invoice", catalogVersion = null),
                )

                val dutchVariant = mediator.send(
                    CreateVariant(
                        id = VariantId(TestIdHelpers.nextVariantId(), templateId),
                        title = "Dutch",
                        description = null,
                        attributes = mapOf("lang" to "dutch"),
                        catalogVersion = null,
                    ),
                )!!

                mediator.send(
                    CreateVariant(
                        id = VariantId(TestIdHelpers.nextVariantId(), templateId),
                        title = "English",
                        description = null,
                        attributes = mapOf("lang" to "english"),
                        catalogVersion = null,
                    ),
                )

                val resolved = variantResolver.resolve(
                    template = templateId,
                    criteria = VariantSelectionCriteria(
                        requiredAttributes = mapOf("lang" to "dutch"),
                    ),
                    catalogVersion = null,
                )

                assertThat(resolved).isEqualTo(dutchVariant.id)
            }
        }

        @Test
        fun `selects variant matching multiple required attributes`() {
            withMediator {
                val tenant = createTenant("Test Tenant")
                val tenantId = TenantId(tenant.id)
                setupAttributeDefinitions(tenantId)
                val templateId = TemplateId(TestIdHelpers.nextTemplateId(), CatalogId.default(tenantId))
                val template = mediator.send(
                    CreateDocumentTemplate(id = templateId, name = "Invoice", catalogVersion = null),
                )

                mediator.send(
                    CreateVariant(
                        id = VariantId(TestIdHelpers.nextVariantId(), templateId),
                        title = "Dutch",
                        description = null,
                        attributes = mapOf("lang" to "dutch"),
                        catalogVersion = null,
                    ),
                )

                val dutchAcme = mediator.send(
                    CreateVariant(
                        id = VariantId(TestIdHelpers.nextVariantId(), templateId),
                        title = "Dutch Acme",
                        description = null,
                        attributes = mapOf("lang" to "dutch", "brand" to "acme"),
                        catalogVersion = null,
                    ),
                )!!

                val resolved = variantResolver.resolve(
                    template = templateId,
                    criteria = VariantSelectionCriteria(
                        requiredAttributes = mapOf("lang" to "dutch", "brand" to "acme"),
                    ),
                    catalogVersion = null,
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
                val tenantId = TenantId(tenant.id)
                setupAttributeDefinitions(tenantId)
                val templateId = TemplateId(TestIdHelpers.nextTemplateId(), CatalogId.default(tenantId))
                val template = mediator.send(
                    CreateDocumentTemplate(id = templateId, name = "Invoice", catalogVersion = null),
                )

                mediator.send(
                    CreateVariant(
                        id = VariantId(TestIdHelpers.nextVariantId(), templateId),
                        title = "Dutch",
                        description = null,
                        attributes = mapOf("lang" to "dutch"),
                        catalogVersion = null,
                    ),
                )

                val dutchAcme = mediator.send(
                    CreateVariant(
                        id = VariantId(TestIdHelpers.nextVariantId(), templateId),
                        title = "Dutch Acme",
                        description = null,
                        attributes = mapOf("lang" to "dutch", "brand" to "acme"),
                        catalogVersion = null,
                    ),
                )!!

                val resolved = variantResolver.resolve(
                    template = templateId,
                    criteria = VariantSelectionCriteria(
                        optionalAttributes = mapOf("lang" to "dutch", "brand" to "acme"),
                    ),
                    catalogVersion = null,
                )

                // dutchAcme: optionalMatches=2 -> score = 2*10 = 20
                // dutch: optionalMatches=1 -> score = 1*10 = 10
                assertThat(resolved).isEqualTo(dutchAcme.id)
            }
        }

        @Test
        fun `an optional match breaks a tie on the required attributes`() {
            withMediator {
                val tenant = createTenant("Test Tenant")
                val tenantId = TenantId(tenant.id)
                setupAttributeDefinitions(tenantId)
                val templateId = TemplateId(TestIdHelpers.nextTemplateId(), CatalogId.default(tenantId))
                val template = mediator.send(
                    CreateDocumentTemplate(id = templateId, name = "Invoice", catalogVersion = null),
                )

                mediator.send(
                    CreateVariant(
                        id = VariantId(TestIdHelpers.nextVariantId(), templateId),
                        title = "Dutch",
                        description = null,
                        attributes = mapOf("lang" to "dutch"),
                        catalogVersion = null,
                    ),
                )

                val dutchAcme = mediator.send(
                    CreateVariant(
                        id = VariantId(TestIdHelpers.nextVariantId(), templateId),
                        title = "Dutch Acme",
                        description = null,
                        attributes = mapOf("lang" to "dutch", "brand" to "acme"),
                        catalogVersion = null,
                    ),
                )!!

                val resolved = variantResolver.resolve(
                    template = templateId,
                    criteria = VariantSelectionCriteria(
                        requiredAttributes = mapOf("lang" to "dutch"),
                        optionalAttributes = mapOf("brand" to "acme"),
                    ),
                    catalogVersion = null,
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
                val tenantId = TenantId(tenant.id)
                setupAttributeDefinitions(tenantId)
                val templateId = TemplateId(TestIdHelpers.nextTemplateId(), CatalogId.default(tenantId))
                val template = mediator.send(
                    CreateDocumentTemplate(id = templateId, name = "Invoice", catalogVersion = null),
                )

                val defaultVariantId = VariantKey.INITIAL

                mediator.send(
                    CreateVariant(
                        id = VariantId(TestIdHelpers.nextVariantId(), templateId),
                        title = "French",
                        description = null,
                        attributes = mapOf("lang" to "french"),
                        catalogVersion = null,
                    ),
                )

                val resolved = variantResolver.resolve(
                    template = templateId,
                    criteria = VariantSelectionCriteria(
                        requiredAttributes = mapOf("lang" to "dutch"),
                    ),
                    catalogVersion = null,
                )

                assertThat(resolved).isEqualTo(defaultVariantId)
            }
        }

        @Test
        fun `default variant with attributes still acts as fallback`() {
            withMediator {
                val tenant = createTenant("Test Tenant")
                val tenantId = TenantId(tenant.id)
                setupAttributeDefinitions(tenantId)
                val templateId = TemplateId(TestIdHelpers.nextTemplateId(), CatalogId.default(tenantId))
                val template = mediator.send(
                    CreateDocumentTemplate(id = templateId, name = "Invoice", catalogVersion = null),
                )

                // Give the auto-created default variant some attributes
                val defaultVariantId = VariantKey.INITIAL
                mediator.send(
                    SetDefaultVariant(variantId = VariantId(defaultVariantId, templateId), catalogVersion = null),
                )

                // Create a non-default variant with different attributes
                mediator.send(
                    CreateVariant(
                        id = VariantId(TestIdHelpers.nextVariantId(), templateId),
                        title = "French",
                        description = null,
                        attributes = mapOf("lang" to "french"),
                        catalogVersion = null,
                    ),
                )

                // Request dutch which no variant has — should fall back to default (even though it has empty attributes)
                val resolved = variantResolver.resolve(
                    template = templateId,
                    criteria = VariantSelectionCriteria(
                        requiredAttributes = mapOf("lang" to "dutch"),
                    ),
                    catalogVersion = null,
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
                val tenantId = TenantId(tenant.id)
                setupAttributeDefinitions(tenantId)
                val templateId = TemplateId(TestIdHelpers.nextTemplateId(), CatalogId.default(tenantId))
                val template = mediator.send(
                    CreateDocumentTemplate(id = templateId, name = "Invoice", catalogVersion = null),
                )

                val variant1 = mediator.send(
                    CreateVariant(
                        id = VariantId(TestIdHelpers.nextVariantId(), templateId),
                        title = "Dutch Acme",
                        description = null,
                        attributes = mapOf("lang" to "dutch", "brand" to "acme"),
                        catalogVersion = null,
                    ),
                )!!

                // Distinct sets that tie on what was asked: identical sets are refused when created.
                val variant2 = mediator.send(
                    CreateVariant(
                        id = VariantId(TestIdHelpers.nextVariantId(), templateId),
                        title = "Dutch Globex",
                        description = null,
                        attributes = mapOf("lang" to "dutch", "brand" to "globex"),
                        catalogVersion = null,
                    ),
                )!!

                val exception = assertThrows<AmbiguousVariantResolutionException> {
                    variantResolver.resolve(
                        template = templateId,
                        criteria = VariantSelectionCriteria(
                            requiredAttributes = mapOf("lang" to "dutch"),
                        ),
                        catalogVersion = null,
                    )
                }
                assertThat(exception.tiedVariantIds).containsExactlyInAnyOrder(variant1.id, variant2.id)
            }
        }
    }

    @Nested
    inner class CombinedRequiredAndOptional {

        @Test
        fun `required filters then optional scores`() {
            withMediator {
                val tenant = createTenant("Test Tenant")
                val tenantId = TenantId(tenant.id)
                setupAttributeDefinitions(tenantId)
                val templateId = TemplateId(TestIdHelpers.nextTemplateId(), CatalogId.default(tenantId))
                val template = mediator.send(
                    CreateDocumentTemplate(id = templateId, name = "Invoice", catalogVersion = null),
                )

                // English variant - won't pass required filter
                mediator.send(
                    CreateVariant(
                        id = VariantId(TestIdHelpers.nextVariantId(), templateId),
                        title = "English Acme",
                        description = null,
                        attributes = mapOf("lang" to "english", "brand" to "acme"),
                        catalogVersion = null,
                    ),
                )

                // Dutch variant - passes required, no optional match
                mediator.send(
                    CreateVariant(
                        id = VariantId(TestIdHelpers.nextVariantId(), templateId),
                        title = "Dutch",
                        description = null,
                        attributes = mapOf("lang" to "dutch"),
                        catalogVersion = null,
                    ),
                )

                // Dutch Acme variant - passes required, matches optional
                val dutchAcme = mediator.send(
                    CreateVariant(
                        id = VariantId(TestIdHelpers.nextVariantId(), templateId),
                        title = "Dutch Acme",
                        description = null,
                        attributes = mapOf("lang" to "dutch", "brand" to "acme"),
                        catalogVersion = null,
                    ),
                )!!

                val resolved = variantResolver.resolve(
                    template = templateId,
                    criteria = VariantSelectionCriteria(
                        requiredAttributes = mapOf("lang" to "dutch"),
                        optionalAttributes = mapOf("brand" to "acme"),
                    ),
                    catalogVersion = null,
                )

                assertThat(resolved).isEqualTo(dutchAcme.id)
            }
        }

        @Test
        fun `only optional attributes with scoring-based selection`() {
            withMediator {
                val tenant = createTenant("Test Tenant")
                val tenantId = TenantId(tenant.id)
                setupAttributeDefinitions(tenantId)
                val templateId = TemplateId(TestIdHelpers.nextTemplateId(), CatalogId.default(tenantId))
                val template = mediator.send(
                    CreateDocumentTemplate(id = templateId, name = "Invoice", catalogVersion = null),
                )

                // Variant with language=dutch - score: 1*10 + 1 = 11
                mediator.send(
                    CreateVariant(
                        id = VariantId(TestIdHelpers.nextVariantId(), templateId),
                        title = "Dutch",
                        description = null,
                        attributes = mapOf("lang" to "dutch"),
                        catalogVersion = null,
                    ),
                )

                // Variant with language=dutch, brand=acme - score: 2*10 + 2 = 22
                val dutchAcme = mediator.send(
                    CreateVariant(
                        id = VariantId(TestIdHelpers.nextVariantId(), templateId),
                        title = "Dutch Acme",
                        description = null,
                        attributes = mapOf("lang" to "dutch", "brand" to "acme"),
                        catalogVersion = null,
                    ),
                )!!

                val resolved = variantResolver.resolve(
                    template = templateId,
                    criteria = VariantSelectionCriteria(
                        optionalAttributes = mapOf("lang" to "dutch", "brand" to "acme"),
                    ),
                    catalogVersion = null,
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
                val tenantId = TenantId(tenant.id)
                val templateId = TemplateId(TestIdHelpers.nextTemplateId(), CatalogId.default(tenantId))
                val template = mediator.send(
                    CreateDocumentTemplate(id = templateId, name = "Invoice", catalogVersion = null),
                )

                val defaultVariantId = VariantKey.INITIAL

                val resolved = variantResolver.resolve(
                    template = templateId,
                    criteria = VariantSelectionCriteria(),
                    catalogVersion = null,
                )

                assertThat(resolved).isEqualTo(defaultVariantId)
            }
        }

        @Test
        fun `variant with extra attributes still matches required subset`() {
            withMediator {
                val tenant = createTenant("Test Tenant")
                val tenantId = TenantId(tenant.id)
                setupAttributeDefinitions(tenantId)
                val templateId = TemplateId(TestIdHelpers.nextTemplateId(), CatalogId.default(tenantId))
                val template = mediator.send(
                    CreateDocumentTemplate(id = templateId, name = "Invoice", catalogVersion = null),
                )

                val dutchAcme = mediator.send(
                    CreateVariant(
                        id = VariantId(TestIdHelpers.nextVariantId(), templateId),
                        title = "Dutch Acme",
                        description = null,
                        attributes = mapOf("lang" to "dutch", "brand" to "acme"),
                        catalogVersion = null,
                    ),
                )!!

                val resolved = variantResolver.resolve(
                    template = templateId,
                    criteria = VariantSelectionCriteria(
                        requiredAttributes = mapOf("lang" to "dutch"),
                    ),
                    catalogVersion = null,
                )

                assertThat(resolved).isEqualTo(dutchAcme.id)
            }
        }
    }
}
