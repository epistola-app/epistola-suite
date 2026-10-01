// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.documents

import app.epistola.suite.attributes.commands.CreateAttributeDefinition
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.common.ids.AttributeId
import app.epistola.suite.common.ids.AttributeKey
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.common.ids.VersionKey
import app.epistola.suite.documents.commands.BatchGenerationItem
import app.epistola.suite.documents.commands.GenerateDocument
import app.epistola.suite.documents.commands.GenerateDocumentBatch
import app.epistola.suite.documents.preview.PreviewTargetResolver
import app.epistola.suite.mediator.execute
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.variants.CreateVariant
import app.epistola.suite.templates.services.VariantSelectionCriteria
import app.epistola.suite.testing.IntegrationTestBase
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.ObjectMapper

/**
 * #1021: choosing a variant by attributes must look at the template in the catalog the request
 * names. It looked in `default` instead, so a template in any other catalog found no variants, or
 * silently got the variant of a same-named template in `default`.
 */
class AttributeSelectionCatalogScopeTest : IntegrationTestBase() {

    @Autowired
    private lateinit var previewTargetResolver: PreviewTargetResolver

    @Autowired
    private lateinit var jdbi: Jdbi

    private val objectMapper = ObjectMapper()
    private val acme = CatalogKey.of("acme")
    private val letter = TemplateKey.of("letter")
    private val english = mapOf("lang" to "english")
    private val englishCriteria = VariantSelectionCriteria(requiredAttributes = english)

    /** `acme/letter` with variants `nld` (lang=dutch) and `eng` (lang=english), and optionally a decoy `default/letter`. */
    private fun setUp(withDecoyInDefault: Boolean): TenantKey = withMediator {
        val tenantKey = createTenant("Attribute scope").id
        val tenantId = TenantId(tenantKey)
        CreateCatalog(tenantKey = tenantKey, id = acme, name = "Acme").execute()
        CreateAttributeDefinition(
            id = AttributeId(AttributeKey.of("lang"), CatalogId(acme, tenantId)),
            displayName = "Language",
            allowedValues = listOf("dutch", "english"),
        ).execute()

        val acmeLetter = TemplateId(letter, CatalogId(acme, tenantId))
        CreateDocumentTemplate(id = acmeLetter, name = "Acme letter").execute()
        CreateVariant(VariantId(VariantKey.of("nld"), acmeLetter), "Dutch", null, mapOf("lang" to "dutch")).execute()
        CreateVariant(VariantId(VariantKey.of("eng"), acmeLetter), "English", null, english).execute()

        if (withDecoyInDefault) {
            // Same template key in `default`, whose English variant happens to be called `nld` —
            // a key that also exists in acme, so the wrong answer would render without an error.
            val defaultLetter = TemplateId(letter, CatalogId.default(tenantId))
            CreateDocumentTemplate(id = defaultLetter, name = "Default letter").execute()
            CreateVariant(VariantId(VariantKey.of("nld"), defaultLetter), "Decoy", null, english).execute()
        }
        tenantKey
    }

    private fun generate(tenantKey: TenantKey) = withMediator {
        GenerateDocument(
            tenantId = tenantKey,
            catalogKey = acme,
            templateId = letter,
            variantSelectionCriteria = englishCriteria,
            versionId = VersionKey.of(1),
            data = objectMapper.createObjectNode(),
            filename = null,
        ).execute()
    }

    @Test
    fun `single generation selects the variant of the template in the requested catalog`() {
        assertThat(generate(setUp(withDecoyInDefault = false)).variantKey).isEqualTo(VariantKey.of("eng"))
    }

    @Test
    fun `single generation is not steered by a same-named template in default`() {
        assertThat(generate(setUp(withDecoyInDefault = true)).variantKey).isEqualTo(VariantKey.of("eng"))
    }

    @Test
    fun `batch generation selects the variant of the template in each item's catalog`() {
        val tenantKey = setUp(withDecoyInDefault = true)

        val batchId = withMediator {
            GenerateDocumentBatch(
                tenantKey,
                listOf(
                    BatchGenerationItem(
                        catalogKey = acme,
                        templateId = letter,
                        variantSelectionCriteria = englishCriteria,
                        versionId = VersionKey.of(1),
                        data = objectMapper.createObjectNode(),
                        filename = null,
                    ),
                ),
            ).execute()
        }

        // Read-only: there is no query that lists a batch's requests by batch id.
        val variantKeys = jdbi.withHandle<List<String>, Exception> { handle ->
            handle.createQuery("SELECT variant_key FROM document_generation_requests WHERE batch_id = :batchId")
                .bind("batchId", batchId)
                .mapTo(String::class.java)
                .list()
        }
        assertThat(variantKeys).containsExactly("eng")
    }

    @Test
    fun `preview selects the variant of the template in the requested catalog`() {
        val tenantKey = setUp(withDecoyInDefault = true)

        val target = withMediator {
            previewTargetResolver.resolve(
                tenantKey = tenantKey,
                catalogKey = acme,
                templateKey = letter,
                data = objectMapper.createObjectNode(),
                variantSelectionCriteria = englishCriteria,
                versionKey = VersionKey.of(1),
            )
        }

        assertThat(target.variantId.key).isEqualTo(VariantKey.of("eng"))
        assertThat(target.variantId.catalogKey).isEqualTo(acme)
    }
}
