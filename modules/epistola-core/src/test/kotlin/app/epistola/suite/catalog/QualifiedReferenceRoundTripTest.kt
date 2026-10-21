// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog

import app.epistola.catalog.archive.CatalogArchiveReader
import app.epistola.suite.attributes.commands.CreateAttributeDefinition
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.commands.ImportCatalogZip
import app.epistola.suite.catalog.graph.CatalogResourceType
import app.epistola.suite.catalog.graph.GetTenantResourceGraph
import app.epistola.suite.catalog.graph.ResourceAddress
import app.epistola.suite.catalog.system.SYSTEM_CATALOG_KEY
import app.epistola.suite.catalog.versions.CatalogDefaultTheme
import app.epistola.suite.catalog.versions.CatalogVersionRef
import app.epistola.suite.catalog.versions.CatalogVersionTargeting
import app.epistola.suite.catalog.versions.CreateCatalogDraft
import app.epistola.suite.catalog.versions.DraftRef
import app.epistola.suite.catalog.versions.SetCatalogDefaultTheme
import app.epistola.suite.common.ids.AttributeId
import app.epistola.suite.common.ids.AttributeKey
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CodeListId
import app.epistola.suite.common.ids.CodeListKey
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.StencilKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TemplateKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.stencils.commands.CreateStencil
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.UpdateDocumentTemplate
import app.epistola.suite.templates.commands.variants.UpdateVariantContent
import app.epistola.suite.templates.model.Node
import app.epistola.suite.templates.model.Slot
import app.epistola.suite.templates.model.TemplateDocument
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.exportReleased
import app.epistola.suite.testing.withRequiredDataExample
import app.epistola.suite.themes.commands.CreateTheme
import app.epistola.template.model.ThemeRefOverride
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * A reference into a catalog of another namespace keeps its qualifier (contract ADR 0003): what a
 * release names as `(registry, namespace, key)` an import, a draft started from it and an export of
 * it name the same way, and the resource fingerprints as it did in the release.
 *
 * Catalog `letters` lives in its tenant's own namespace and refers to the bundled `system` catalog
 * (namespace `epistola`) and to catalog `parts` (namespace `parts-shop` of registry
 * `registry.example.com`), by every reference shape a catalog carries.
 */
class QualifiedReferenceRoundTripTest : IntegrationTestBase() {

    @Autowired private lateinit var objectMapper: ObjectMapper

    @Autowired private lateinit var contentBuilder: CatalogContentBuilder

    @Autowired private lateinit var fingerprints: CatalogFingerprintService

    @Autowired private lateinit var targeting: CatalogVersionTargeting

    private val letters = CatalogKey.of("letters")
    private val parts = CatalogKey.of("parts")
    private val partsNamespace = "parts-shop"
    private val partsRegistry = "registry.example.com"
    private val badge = "01966a00-0000-7000-8000-000000000010"

    private fun systemFont() = mapOf("fontFamily" to mapOf("key" to "inter", "catalogKey" to SYSTEM_CATALOG_KEY.value))

    private fun model(): TemplateDocument = TemplateDocument(
        modelVersion = 1,
        root = "root",
        nodes = mapOf(
            "root" to Node(id = "root", type = "root", slots = listOf("root-slot")),
            "title" to Node(id = "title", type = "text", styles = systemFont()),
            "header" to Node(id = "header", type = "stencil", slots = listOf("header-slot"), props = mapOf("stencilKey" to "header", "catalogKey" to parts.value)),
            "logo" to Node(id = "logo", type = "image", props = mapOf("imageKey" to badge, "catalogKey" to SYSTEM_CATALOG_KEY.value)),
        ),
        slots = mapOf(
            "root-slot" to Slot(id = "root-slot", nodeId = "root", name = "children", children = listOf("title", "header", "logo")),
            "header-slot" to Slot(id = "header-slot", nodeId = "header", name = "children", children = emptyList()),
        ),
        themeRef = ThemeRefOverride(themeKey = "default", catalogKey = SYSTEM_CATALOG_KEY.value),
    )

    /** What tenant [a] released: `parts` and `letters`, as archives. */
    private class Released(val parts: ByteArray, val letters: ByteArray)

    private fun publish(a: TenantKey): Released = withMediator {
        val tenant = TenantId(a)
        CreateCatalog(tenantKey = a, id = parts, name = "Parts", namespace = partsNamespace, registry = partsRegistry).execute()
        CreateStencil(StencilId(StencilKey.of("header"), CatalogId(parts, tenant)), "Header").execute()

        CreateCatalog(tenantKey = a, id = letters, name = "Letters").execute()
        declareDependency(a, letters, on = SYSTEM_CATALOG_KEY)
        declareDependency(a, letters, on = parts)
        val own = CatalogId(letters, tenant)
        CreateTheme(ThemeId(ThemeKey.of("brand"), own), "Brand", documentStyles = systemFont()).execute()
        CreateAttributeDefinition(
            id = AttributeId(AttributeKey.of("preferred-language"), own),
            displayName = "Preferred language",
            codeListId = CodeListId(CodeListKey.of("iso-639-1"), CatalogId(SYSTEM_CATALOG_KEY, tenant)),
        ).execute()
        val template = TemplateId(TemplateKey.of("letter"), own)
        CreateDocumentTemplate(template, "Letter").execute().withRequiredDataExample()
        UpdateDocumentTemplate(template, themeId = ThemeKey.of("default"), themeCatalogKey = SYSTEM_CATALOG_KEY).execute()
        UpdateVariantContent(VariantId(VariantKey.INITIAL, template), model()).execute()
        SetCatalogDefaultTheme(a, letters, CatalogDefaultTheme(ThemeKey.of("default"), SYSTEM_CATALOG_KEY)).execute()

        val lettersZip = mediator.exportReleased(own).zipBytes
        Released(parts = mediator.exportReleased(CatalogId(parts, tenant)).zipBytes, letters = lettersZip)
    }

    private fun entries(bytes: ByteArray): Map<String, ByteArray> = buildMap {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                if (!e.isDirectory) put(e.name, zip.readAllBytes())
                e = zip.nextEntry
            }
        }
    }

    private fun detail(zip: ByteArray, key: String): JsonNode = objectMapper.readTree(entries(zip).getValue("resources/$key.json")).get("resource")

    private fun detail(content: CatalogContent, key: String): JsonNode = objectMapper.valueToTree<JsonNode>(content.resourceDetails.getValue(key).resource)

    private fun releaseVersion(zip: ByteArray): String = objectMapper.readTree(entries(zip).getValue("catalog.json")).get("release").get("version").asString()

    private fun archiveFingerprints(zip: ByteArray): Map<String, String> = CatalogArchiveReader.read(ByteArrayInputStream(zip)).archive!!.use { fingerprints.resourceFingerprints(it) }

    /**
     * Imports what [a] released into a fresh tenant and checks, for resource [key], that [reference]
     * reads the same from the release, from the received catalog built from its rows and from a
     * draft started from the release, and that the resource fingerprints as it did in the release.
     */
    private fun roundTrip(name: String, key: String, expected: Map<String, String?>, reference: (JsonNode) -> JsonNode) {
        val a = createTenant("$name A").id
        val b = createTenant("$name B").id
        val released = publish(a)
        assertThat(qualifier(reference(detail(released.letters, key)))).isEqualTo(expected)

        withMediator {
            ImportCatalogZip(tenantKey = b, zipBytes = released.parts).execute()
            ImportCatalogZip(tenantKey = b, zipBytes = released.letters).execute()
            val received = contentBuilder.build(b, letters)
            assertThat(qualifier(reference(detail(received, key)))).isEqualTo(expected)
            assertThat(fingerprints.resourceFingerprints(received).getValue(key)).isEqualTo(archiveFingerprints(released.letters).getValue(key))

            val draft = CreateCatalogDraft(b, letters, name = "Redesign", from = releaseVersion(released.letters)).execute()
            val id = targeting.resolve(CatalogVersionRef(b, letters, DraftRef.Id(draft.versionId)))
            val drafted = targeting.within(id) { contentBuilder.build(b, letters) }
            assertThat(qualifier(reference(detail(drafted, key)))).isEqualTo(expected)
        }
    }

    private fun qualifier(node: JsonNode, catalogKey: String = "catalogKey", namespace: String = "namespace", registry: String = "registry"): Map<String, String?> = mapOf(
        "catalogKey" to node.path(catalogKey).takeIf { it.isString }?.asString(),
        "namespace" to node.path(namespace).takeIf { it.isString }?.asString(),
        "registry" to node.path(registry).takeIf { it.isString }?.asString(),
    )

    private val system = mapOf("catalogKey" to "system", "namespace" to "epistola", "registry" to null)
    private val inParts = mapOf("catalogKey" to "parts", "namespace" to partsNamespace, "registry" to partsRegistry)

    /** Where [source] (in `letters` of tenant [b]) points to, by the tenant's resource graph. */
    private fun resolvedTargets(b: TenantKey, source: ResourceAddress): Set<ResourceAddress> = GetTenantResourceGraph(b).query().edges
        .filter { it.source == source }
        .mapNotNull { it.target }
        .toSet()

    @Test
    fun `a template's theme in another namespace keeps its qualifier`() {
        roundTrip("Qualified theme", "template/letter", system) { template ->
            objectMapper.createObjectNode().apply {
                set("catalogKey", template.get("themeCatalogKey"))
                template.get("themeNamespace")?.let { set("namespace", it) }
                template.get("themeRegistry")?.let { set("registry", it) }
            }
        }
    }

    @Test
    fun `a code-list binding into another namespace keeps its qualifier`() {
        roundTrip("Qualified binding", "attribute/preferred-language", system) { it.get("codeListBinding") }
    }

    @Test
    fun `a theme's font in another namespace keeps its qualifier`() {
        roundTrip("Qualified theme font", "theme/brand", system) { it.get("documentStyles").get("fontFamily") }
    }

    @Test
    fun `a template's font in another namespace keeps its qualifier`() {
        roundTrip("Qualified node font", "template/letter", system) { it.get("templateModel").get("nodes").get("title").get("styles").get("fontFamily") }
    }

    @Test
    fun `a template's theme override in another namespace keeps its qualifier`() {
        roundTrip("Qualified override", "template/letter", system) { it.get("templateModel").get("themeRef") }
    }

    /** The image component declares no `namespace` or `registry` prop, so the wire names an image's catalog by key alone. */
    @Test
    fun `an image in another namespace travels by catalog key, which is all the wire lets it name`() {
        roundTrip("Qualified image", "template/letter", system + ("namespace" to null)) { it.get("templateModel").get("nodes").get("logo").get("props") }
    }

    @Test
    fun `a stencil in another namespace and registry keeps its qualifier`() {
        roundTrip("Qualified stencil", "template/letter", inParts) { it.get("templateModel").get("nodes").get("header").get("props") }
    }

    /**
     * A catalog's default theme (contract ADR 0007) in another namespace: named in the manifest's
     * `catalog.defaultTheme` like the references in resources are, the registry only where it
     * differs, and kept by an import and a draft started from the release.
     */
    @Test
    fun `a catalog's default theme in another namespace keeps its qualifier`() {
        val a = createTenant("Qualified default theme A").id
        val b = createTenant("Qualified default theme B").id
        val released = publish(a)
        fun defaultTheme(manifest: JsonNode) = manifest.get("catalog").get("defaultTheme")
        val sent = defaultTheme(objectMapper.readTree(entries(released.letters).getValue("catalog.json")))
        assertThat(qualifier(sent)).isEqualTo(system)
        assertThat(sent.get("key").asString()).isEqualTo("default")

        withMediator {
            ImportCatalogZip(tenantKey = b, zipBytes = released.parts).execute()
            ImportCatalogZip(tenantKey = b, zipBytes = released.letters).execute()
            assertThat(objectMapper.valueToTree<JsonNode>(contentBuilder.build(b, letters).catalog.defaultTheme)).isEqualTo(sent)

            val draft = CreateCatalogDraft(b, letters, name = "Redesign", from = releaseVersion(released.letters)).execute()
            val id = targeting.resolve(CatalogVersionRef(b, letters, DraftRef.Id(draft.versionId)))
            assertThat(objectMapper.valueToTree<JsonNode>(targeting.within(id) { contentBuilder.build(b, letters) }.catalog.defaultTheme)).isEqualTo(sent)
        }
    }

    @Test
    fun `the received references resolve to the catalogs they name`() {
        val a = createTenant("Qualified resolve A").id
        val b = createTenant("Qualified resolve B").id
        val released = publish(a)
        withMediator {
            ImportCatalogZip(tenantKey = b, zipBytes = released.parts).execute()
            ImportCatalogZip(tenantKey = b, zipBytes = released.letters).execute()

            assertThat(resolvedTargets(b, ResourceAddress(CatalogResourceType.TEMPLATE, letters.value, "letter"))).contains(
                ResourceAddress(CatalogResourceType.THEME, "system", "default"),
                ResourceAddress(CatalogResourceType.FONT, "system", "inter"),
                ResourceAddress(CatalogResourceType.STENCIL, "parts", "header"),
            )
            assertThat(resolvedTargets(b, ResourceAddress(CatalogResourceType.THEME, letters.value, "brand")))
                .contains(ResourceAddress(CatalogResourceType.FONT, "system", "inter"))
            assertThat(resolvedTargets(b, ResourceAddress(CatalogResourceType.ATTRIBUTE, letters.value, "preferred-language")))
                .containsExactly(ResourceAddress(CatalogResourceType.CODE_LIST, "system", "iso-639-1"))
        }
    }
}
