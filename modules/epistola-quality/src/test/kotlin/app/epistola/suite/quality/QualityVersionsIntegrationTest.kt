// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.quality

import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.CreateCatalogDraft
import app.epistola.suite.catalog.releases.DraftRef
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.quality.commands.RunQualityChecks
import app.epistola.suite.quality.queries.GetFindingsForSubject
import app.epistola.suite.quality.queries.SubjectFindings
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.UpdateDocumentTemplate
import app.epistola.suite.templates.commands.variants.CreateVariant
import app.epistola.suite.templates.commands.variants.UpdateVariantContent
import app.epistola.suite.tenants.commands.SetTenantDefaultLocale
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestIdHelpers
import app.epistola.suite.testing.default
import app.epistola.suite.testing.releaseNext
import app.epistola.suite.themes.commands.CreateTheme
import app.epistola.suite.themes.commands.UpdateTheme
import app.epistola.template.model.Node
import app.epistola.template.model.Slot
import app.epistola.template.model.TemplateDocument
import app.epistola.template.model.ThemeRefInherit
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * Findings per catalog version (ADR 0044): a check is stored under what it read, the variant's input
 * key in the version it checked, and every version shows, per variant and source, the check with its
 * key there, else the newest as outdated. Each test drives the real check runner and the example
 * source, which reports an empty text block.
 */
class QualityVersionsIntegrationTest : IntegrationTestBase() {
    @Autowired
    private lateinit var jdbi: Jdbi

    private val example = QualitySourceId("example")

    @Test
    fun `a check of the next version shows there, current`() {
        val variantId = newVariant()
        save(variantId, EMPTY)
        check(variantId)

        val next = read(variantId)
        assertThat(next.example()).singleElement().satisfies({ assertThat(it.outdated).isFalse() })
        assertThat(next.checks.single { it.sourceId == example }.current).isTrue()
    }

    @Test
    fun `a draft started from the next version shows its findings at once, and its own edit outdates them there only`() {
        val variantId = newVariant()
        save(variantId, EMPTY)
        check(variantId)
        val draft = startDraft(variantId)

        // Same content, same key: the next version's check is the draft's too, with no run.
        assertThat(read(variantId, draft).example()).singleElement().satisfies({ assertThat(it.outdated).isFalse() })

        save(variantId, FILLED, draft)

        assertThat(read(variantId, draft).example()).singleElement().satisfies({ assertThat(it.outdated).isTrue() })
        assertThat(read(variantId).example()).singleElement().satisfies({ assertThat(it.outdated).isFalse() })
    }

    @Test
    fun `checking a draft leaves the findings of the next version standing`() {
        val variantId = newVariant()
        save(variantId, EMPTY)
        check(variantId)
        val draft = startDraft(variantId)
        save(variantId, FILLED, draft)

        check(variantId, draft)

        assertThat(read(variantId, draft).example()).isEmpty()
        assertThat(read(variantId, draft).checks.single { it.sourceId == example }.current).isTrue()
        // The next version still reads the empty block, and its check is still the one it shows.
        assertThat(read(variantId).example()).singleElement().satisfies({ assertThat(it.outdated).isFalse() })
    }

    @Test
    fun `a release keeps its findings while the next version moves on`() {
        val variantId = newVariant()
        save(variantId, EMPTY)
        check(variantId)
        val release = withMediator { mediator.releaseNext(variantId.catalogId) }
        val released = CatalogReleaseRef(variantId.catalogId, DraftRef.Released(release))

        // The next version continues on the release with the same content: its findings show at once.
        assertThat(read(variantId).example()).singleElement().satisfies({ assertThat(it.outdated).isFalse() })

        save(variantId, FILLED)
        check(variantId)

        assertThat(read(variantId).example()).isEmpty()
        assertThat(read(variantId, released).example()).singleElement().satisfies({ assertThat(it.outdated).isFalse() })
    }

    @Test
    fun `editing one variant leaves another variant's findings current`() {
        val first = newVariant()
        val second = withMediator {
            VariantId(TestIdHelpers.nextVariantId(), first.templateId).also { CreateVariant(id = it, title = "Second", description = null).execute() }
        }
        save(first, EMPTY)
        save(second, EMPTY)
        check(first)
        check(second)

        save(first, FILLED)

        assertThat(read(first).example()).singleElement().satisfies({ assertThat(it.outdated).isTrue() })
        assertThat(read(second).example()).singleElement().satisfies({ assertThat(it.outdated).isFalse() })
    }

    /** What a check reads beyond the variant: the theme its template renders with, by its content in the version. */
    @Test
    fun `changing the theme the template uses outdates its findings`() {
        val variantId = newVariant()
        val themeId = ThemeId(ThemeKey.of("house"), variantId.catalogId)
        withMediator {
            CreateTheme(id = themeId, name = "House").execute()
            UpdateDocumentTemplate(variantId.templateId, themeId = themeId.key, themeCatalog = variantId.catalogId).execute()
        }
        save(variantId, EMPTY)
        check(variantId)
        assertThat(read(variantId).example().single().outdated).isFalse()

        withMediator { UpdateTheme(themeId, description = "Warmer colours").execute() }

        assertThat(read(variantId).example().single().outdated).isTrue()
    }

    /** A variant without a language of its own is checked in the tenant's. */
    @Test
    fun `changing the tenant's language outdates its findings`() {
        val variantId = newVariant()
        save(variantId, EMPTY)
        check(variantId)

        withMediator { SetTenantDefaultLocale(variantId.tenantKey, "nl-NL").execute() }

        assertThat(read(variantId).example().single().outdated).isTrue()
    }

    /** A check under a key no version has any more is kept by nobody: the next check of the source takes it. */
    @Test
    fun `a superseded check goes with its findings, a released one stays`() {
        val variantId = newVariant()
        save(variantId, EMPTY)
        check(variantId)
        withMediator { mediator.releaseNext(variantId.catalogId) }
        save(variantId, FILLED)
        check(variantId)
        val releasedKey = checkKeys(variantId).single { it != read(variantId).currentInputKey }

        save(variantId, EMPTY_TWICE)
        check(variantId)

        // The release's key and the next version's: the check in between, of FILLED, is gone.
        assertThat(checkKeys(variantId)).containsExactlyInAnyOrder(releasedKey, read(variantId).currentInputKey)
    }

    private fun SubjectFindings.example() = findings.filter { it.sourceId == example }

    private fun checkKeys(variantId: VariantId): List<String> = jdbi.withHandle<List<String>, Exception> { handle ->
        // What is stored, not what a version shows: no query reads checks no version shows.
        handle.createQuery("SELECT input_key FROM quality_checks WHERE tenant_key = :t AND variant_key = :v AND source_id = :s")
            .bind("t", variantId.tenantKey)
            .bind("v", variantId.key.value)
            .bind("s", example.value)
            .mapTo(String::class.java)
            .list()
    }

    private fun newVariant(): VariantId {
        val tenant = createTenant("Quality Versions")
        return withMediator {
            val templateId = TemplateId(TestIdHelpers.nextTemplateId(), CatalogId.default(TenantId(tenant.id)))
            CreateDocumentTemplate(id = templateId, name = "Invoice").execute()
            VariantId(TestIdHelpers.nextVariantId(), templateId).also { CreateVariant(id = it, title = "Default", description = null).execute() }
        }
    }

    private fun startDraft(variantId: VariantId): CatalogReleaseRef {
        val draft = withMediator { CreateCatalogDraft(variantId.catalogId, name = "Redesign", from = DraftRef.NEXT).execute() }
        return CatalogReleaseRef(variantId.catalogId, DraftRef.Id(draft.versionId))
    }

    private fun save(variantId: VariantId, document: TemplateDocument, version: CatalogReleaseRef? = null) = withMediator {
        UpdateVariantContent(variantId, document, catalogVersion = version).execute()
    }

    private fun check(variantId: VariantId, version: CatalogReleaseRef? = null) = withMediator {
        RunQualityChecks(variantId, sourceIds = setOf(example), catalogVersion = version).execute()
    }

    private fun read(variantId: VariantId, version: CatalogReleaseRef? = null): SubjectFindings = withMediator {
        GetFindingsForSubject(variantId.catalogId, variantId.templateKey, variantId.key.value, version).query()
    }

    private companion object {
        val EMPTY = documentWithTexts("")
        val FILLED = documentWithTexts("Dear customer, your invoice is attached.")
        val EMPTY_TWICE = documentWithTexts("", "")

        fun documentWithTexts(vararg texts: String): TemplateDocument {
            val root = Node(id = "root", type = "root", slots = listOf("root-slot"))
            val nodes = texts.mapIndexed { i, text ->
                Node(
                    id = "node-${i + 1}",
                    type = "text",
                    slots = emptyList(),
                    props = mapOf(
                        "content" to mapOf(
                            "type" to "doc",
                            "content" to listOf(mapOf("type" to "paragraph", "content" to listOf(mapOf("type" to "text", "text" to text)))),
                        ),
                    ),
                )
            }
            return TemplateDocument(
                modelVersion = 1,
                root = root.id,
                nodes = (listOf(root) + nodes).associateBy { it.id },
                slots = mapOf("root-slot" to Slot("root-slot", root.id, "children", nodes.map { it.id })),
                themeRef = ThemeRefInherit(),
            )
        }
    }
}
