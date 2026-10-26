// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.generation.release

import app.epistola.suite.attributes.commands.CreateAttributeDefinition
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.MarkCatalogReady
import app.epistola.suite.catalog.commands.ReleaseCatalogDraft
import app.epistola.suite.catalog.revisions.ReleaseContentAssembler
import app.epistola.suite.common.ids.AttributeId
import app.epistola.suite.common.ids.AttributeKey
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CatalogKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.common.ids.VersionId
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.variants.CreateVariant
import app.epistola.suite.templates.commands.variants.MarkVariantReady
import app.epistola.suite.templates.commands.variants.UpdateVariantContent
import app.epistola.suite.templates.queries.variants.GetVariantContent
import app.epistola.suite.tenants.queries.GetTenant
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestTemplateBuilder
import app.epistola.suite.testing.testCatalog
import app.epistola.suite.testing.withRequiredDataExample
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.statement.SqlLogger
import org.jdbi.v3.core.statement.SqlStatements
import org.jdbi.v3.core.statement.StatementContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.sql.SQLException

/**
 * What it costs to read a template out of a release, counted in statements.
 *
 * Generation reads the released template twice per request — once to choose the variant, once to
 * render it — and does so for every request against the same release. Revisions are immutable, so
 * neither read needs to go back to the database after the first, and reading one template must not
 * cost a query per variant.
 */
class ReleaseReadCostTest : IntegrationTestBase() {

    @Autowired
    private lateinit var assembler: ReleaseContentAssembler

    @Autowired
    private lateinit var resolver: ReleaseTargetResolver

    @Autowired
    private lateinit var source: ReleaseRenderSource

    @Autowired
    private lateinit var jdbi: Jdbi

    @Test
    fun `a template with several variants is read in one query per revision level, not one per variant`() {
        val catalog = releasedLetterWithThreeVariants("cost-levels")

        val revisionReads = countStatements("FROM resource_contents") {
            assembler.readResource(testCatalog(catalog.tenantKey, catalog.key), "1.0.0", "template", "letter")
        }

        // The template's own revision, then every variant model it references, together.
        assertThat(revisionReads).isEqualTo(2)
    }

    @Test
    fun `choosing the variant and rendering it read the released template once`() {
        val catalog = releasedLetterWithThreeVariants("cost-shared")
        val tenant = withMediator { GetTenant(catalog.tenantKey).query()!! }

        val templateLookups = countStatements("SELECT content_hash, resource_id FROM release_wire_resources") {
            val target = resolver.resolveLatest(testCatalog(catalog.tenantKey, catalog.key), TemplateKey.of("letter"), VariantKey.of("nld"), criteria = null)
            source.resolve(tenant, target.release, "letter", target.variantKey.value)
            // A second request against the same release.
            resolver.resolveLatest(testCatalog(catalog.tenantKey, catalog.key), TemplateKey.of("letter"), VariantKey.of("eng"), criteria = null)
        }

        assertThat(templateLookups).isEqualTo(1)
    }

    /** Runs [block] and counts the statements this thread executed whose SQL contains [fragment]. */
    private fun countStatements(fragment: String, block: () -> Unit): Int {
        val statements = jdbi.getConfig(SqlStatements::class.java)
        val original = statements.sqlLogger
        val thread = Thread.currentThread()
        var count = 0
        statements.sqlLogger = object : SqlLogger {
            override fun logBeforeExecution(context: StatementContext) {
                if (Thread.currentThread() == thread && fragment in context.renderedSql) count++
                original.logBeforeExecution(context)
            }

            override fun logAfterExecution(context: StatementContext) = original.logAfterExecution(context)

            override fun logException(context: StatementContext, ex: SQLException) = original.logException(context, ex)
        }
        try {
            block()
        } finally {
            statements.sqlLogger = original
        }
        return count
    }

    /** `letter` with its default variant plus `nld` and `eng`, all three published, released as 1.0.0. */
    private fun releasedLetterWithThreeVariants(slug: String): CatalogId = withMediator {
        val tenantKey = createTenant(slug).id
        val catalog = testCatalog(TenantId(tenantKey), CatalogKey.of(slug))
        CreateCatalog(tenantKey = tenantKey, id = catalog.key, name = slug).execute()
        CreateAttributeDefinition(
            id = AttributeId(AttributeKey.of("lang"), catalog),
            displayName = "Language",
            allowedValues = listOf("dutch", "english"),
        ).execute()
        val letter = TemplateId(TemplateKey.of("letter"), catalog)
        CreateDocumentTemplate(letter, "Letter").execute().withRequiredDataExample()
        CreateVariant(VariantId(VariantKey.of("nld"), letter), "Dutch", null, mapOf("lang" to "dutch")).execute()
        CreateVariant(VariantId(VariantKey.of("eng"), letter), "English", null, mapOf("lang" to "english")).execute()
        for (variant in listOf(VariantKey.INITIAL, VariantKey.of("nld"), VariantKey.of("eng"))) {
            val id = VariantId(variant, letter)
            UpdateVariantContent(id, TestTemplateBuilder.buildMinimal(name = "Letter ${variant.value}")).execute()
            MarkVariantReady(id).execute()
        }
        MarkCatalogReady(testCatalog(tenantKey, catalog.key)).execute()
        ReleaseCatalogDraft(catalog = testCatalog(tenantKey, catalog.key), version = "1.0.0").execute()
        catalog
    }
}
