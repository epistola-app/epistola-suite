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
import app.epistola.suite.catalog.releases.CatalogDefaultTheme
import app.epistola.suite.catalog.releases.CatalogReleaseRef
import app.epistola.suite.catalog.releases.CatalogReleaseTargeting
import app.epistola.suite.catalog.releases.CreateCatalogDraft
import app.epistola.suite.catalog.releases.DraftRef
import app.epistola.suite.catalog.releases.SetCatalogDefaultTheme
import app.epistola.suite.catalog.system.SYSTEM_CATALOG_KEY
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
import app.epistola.suite.testing.TestNamespaces
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
 * A reference into another catalog names it by the alias the catalog's dependency list declares
 * (contract ADR 0003, amendment of 2026-10-22; ADR 0034), and the declaration says which catalog that
 * is by registry, namespace and key: what a release names that way an import, a draft started from it
 * and an export of it name the same way, and the resource fingerprints as it did in the release. This
 * installation declares every catalog under its key, so the alias is the catalog's key, and no
 * reference carries a namespace or registry.
 *
 * Catalog `letters` lives in its tenant's own namespace and refers to the bundled `system` catalog
 * (namespace `epistola`) and to catalog `parts` (namespace `parts-shop` of registry
 * `registry.example.com`), by every reference shape a catalog carries.
 */
class AliasReferenceRoundTripTest : IntegrationTestBase() {

    @Autowired private lateinit var objectMapper: ObjectMapper

    @Autowired private lateinit var contentBuilder: CatalogContentBuilder

    @Autowired private lateinit var fingerprints: CatalogFingerprintService

    @Autowired private lateinit var targeting: CatalogReleaseTargeting

    @Autowired private lateinit var assembler: app.epistola.suite.catalog.revisions.ReleaseContentAssembler

    @Autowired private lateinit var renderSource: app.epistola.suite.generation.release.ReleaseRenderSource

    @Autowired private lateinit var jdbi: org.jdbi.v3.core.Jdbi

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
        TestNamespaces.grant(a, partsNamespace, authority = partsRegistry).execute()
        CreateCatalog(tenantKey = a, id = parts, name = "Parts", namespace = partsNamespace, registry = partsRegistry).execute()
        CreateStencil(StencilId(StencilKey.of("header"), CatalogId(parts, tenant)), "Header").execute()

        CreateCatalog(tenantKey = a, id = letters, name = "Letters", namespace = TestNamespaces.of(a).namespace).execute()
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
            val id = targeting.resolve(CatalogReleaseRef(b, letters, DraftRef.Id(draft.versionId)))
            val drafted = targeting.within(id) { contentBuilder.build(b, letters) }
            assertThat(qualifier(reference(detail(drafted, key)))).isEqualTo(expected)
        }
    }

    private fun qualifier(node: JsonNode, catalogKey: String = "catalogKey", namespace: String = "namespace", registry: String = "registry"): Map<String, String?> = mapOf(
        "catalogKey" to node.path(catalogKey).takeIf { it.isString }?.asString(),
        "namespace" to node.path(namespace).takeIf { it.isString }?.asString(),
        "registry" to node.path(registry).takeIf { it.isString }?.asString(),
    )

    /** A reference into `system` or `parts`: by its alias, the catalog's key, and nothing else. */
    private val system = mapOf("catalogKey" to "system", "namespace" to null, "registry" to null)
    private val inParts = mapOf("catalogKey" to "parts", "namespace" to null, "registry" to null)

    /** Where [source] (in `letters` of tenant [b]) points to, by the tenant's resource graph. */
    private fun resolvedTargets(b: TenantKey, source: ResourceAddress): Set<ResourceAddress> = GetTenantResourceGraph(b).query().edges
        .filter { it.source == source }
        .mapNotNull { it.target }
        .toSet()

    @Test
    fun `a template's theme in another catalog names its alias`() {
        roundTrip("Qualified theme", "template/letter", system) { template ->
            objectMapper.createObjectNode().apply {
                set("catalogKey", template.get("themeCatalogKey"))
                template.get("themeNamespace")?.let { set("namespace", it) }
                template.get("themeRegistry")?.let { set("registry", it) }
            }
        }
    }

    @Test
    fun `a code-list binding into another catalog names its alias`() {
        roundTrip("Qualified binding", "attribute/preferred-language", system) { it.get("codeListBinding") }
    }

    @Test
    fun `a theme's font in another catalog names its alias`() {
        roundTrip("Qualified theme font", "theme/brand", system) { it.get("documentStyles").get("fontFamily") }
    }

    @Test
    fun `a template's font in another catalog names its alias`() {
        roundTrip("Qualified node font", "template/letter", system) { it.get("templateModel").get("nodes").get("title").get("styles").get("fontFamily") }
    }

    @Test
    fun `a template's theme override in another catalog names its alias`() {
        roundTrip("Qualified override", "template/letter", system) { it.get("templateModel").get("themeRef") }
    }

    @Test
    fun `an image in another catalog names its alias`() {
        roundTrip("Qualified image", "template/letter", system) { it.get("templateModel").get("nodes").get("logo").get("props") }
    }

    @Test
    fun `a stencil in another namespace and registry names its alias`() {
        roundTrip("Qualified stencil", "template/letter", inParts) { it.get("templateModel").get("nodes").get("header").get("props") }
    }

    /**
     * A catalog's default theme (contract ADR 0007) in another catalog: named in the manifest's
     * `catalog.defaultTheme` by alias like the references in resources are, and kept by an import and
     * a draft started from the release.
     */
    @Test
    fun `a catalog's default theme in another catalog names its alias`() {
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
            val id = targeting.resolve(CatalogReleaseRef(b, letters, DraftRef.Id(draft.versionId)))
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

    /**
     * The release names each catalog its content uses once, in `catalogDependencies`: the alias the
     * content names it by, which is its key, and the catalog in full.
     */
    @Test
    fun `the release declares each catalog it uses under its key, with its name in full`() {
        val a = createTenant("Alias declarations").id
        val released = publish(a)
        val declared = objectMapper.readTree(entries(released.letters).getValue("catalog.json")).get("catalogDependencies")
            .associate { it.get("alias").asString() to listOf(it.get("registry").asString(), it.get("namespace").asString(), it.get("catalogKey").asString()) }
        assertThat(declared).isEqualTo(
            mapOf(
                "system" to listOf("exchange.epistola.app", "epistola", "system"),
                "parts" to listOf(partsRegistry, partsNamespace, "parts"),
            ),
        )
        assertThat(String(entries(released.letters).filterKeys { it.startsWith("resources/") || it == "catalog.json" }.values.fold(ByteArray(0)) { all, b -> all + b }))
            .`as`("no reference names a namespace or registry")
            .doesNotContain("\"themeNamespace\"").doesNotContain("\"themeRegistry\"")
    }

    /**
     * An archive that names a catalog it uses by another alias than its key cannot be stored as it is
     * received here (references are stored by catalog key until catalogs are told apart by namespace
     * and key), so it is refused, saying which, rather than stored with references that name no
     * catalog of this tenant.
     */
    @Test
    fun `an archive naming a catalog it uses by another alias than its key is refused`() {
        val a = createTenant("Other alias A").id
        val b = createTenant("Other alias B").id
        val released = publish(a)
        val renamed = withAlias(released.letters, from = "parts", to = "shop")
        withMediator {
            ImportCatalogZip(tenantKey = b, zipBytes = released.parts).execute()
            org.assertj.core.api.Assertions.assertThatThrownBy { ImportCatalogZip(tenantKey = b, zipBytes = renamed).execute() }
                .isInstanceOfSatisfying(app.epistola.suite.catalog.commands.CatalogImportValidationException::class.java) { refused ->
                    assertThat(refused.findings.map { it.code }).contains(app.epistola.suite.catalog.dependencies.DependencyAliases.ALIAS_UNSUPPORTED)
                    assertThat(refused.findings.first { it.code == app.epistola.suite.catalog.dependencies.DependencyAliases.ALIAS_UNSUPPORTED }.message)
                        .contains("$partsRegistry/$partsNamespace/parts as 'shop'")
                }
            assertThat(app.epistola.suite.catalog.queries.GetCatalog(b, letters).query()).`as`("nothing was received").isNull()
        }
    }

    /**
     * A release retained while an earlier draft of wire v8 let references carry a namespace and
     * registry keeps those bytes (a release is immutable), and still reads as content is now: without
     * them, its references resolving through the catalog key they keep.
     */
    @Test
    fun `a release retained with qualified references reads without them and renders`() {
        val a = createTenant("Retained qualifiers").id
        val released = publish(a)
        val version = releaseVersion(released.letters)
        // No command writes a qualifier any more: the retained shape of an earlier draft of wire v8 is put back by hand.
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                UPDATE resource_revisions m
                SET payload = jsonb_set(jsonb_set(m.payload, '{nodes,header,props,namespace}', '"$partsNamespace"'),
                                        '{nodes,header,props,registry}', '"$partsRegistry"')
                FROM release_entries e
                JOIN resource_revisions t ON t.tenant_key = e.tenant_key AND t.digest = e.revision_digest
                WHERE e.tenant_key = :t AND e.resource_type = 'template' AND e.resource_key = 'letter'
                  AND m.tenant_key = t.tenant_key AND m.digest = t.payload -> 'templateModel' ->> 'revisionDigest'
                """,
            ).bind("t", a).execute()
            assertThat(handle.createQuery("SELECT count(*) FROM resource_revisions WHERE tenant_key = :t AND payload::text LIKE '%registry.example.com%'").bind("t", a).mapTo(Int::class.java).one())
                .`as`("the retained bytes carry the qualifiers").isGreaterThan(0)
        }
        withMediator {
            val template = assembler.readResource(a, letters, version, "template", "letter") as app.epistola.catalog.protocol.TemplateResource
            assertThat(template.templateModel.nodes.getValue("header").props).isEqualTo(mapOf("stencilKey" to "header", "catalogKey" to "parts"))
            val whole = assembler.assemble(a, letters, version)!!
            assertThat(objectMapper.writeValueAsString(whole.content.resourceDetails.getValue("template/letter"))).doesNotContain(partsRegistry)

            val tenant = app.epistola.suite.tenants.queries.GetTenant(a).query()!!
            val inputs = renderSource.resolve(tenant, app.epistola.suite.generation.release.ReleaseRef(letters, version), "letter", VariantKey.INITIAL.value)
            assertThat(inputs.templateModel.nodes.values.map { it.type }).`as`("the stencil of 'parts' it names is expanded").contains("stencil")
        }
    }

    /**
     * [zip] with the declaration of catalog [from] aliased [to], every reference to it renamed with
     * it, and fingerprinted again: a valid archive, as another producer may write one.
     */
    private fun withAlias(zip: ByteArray, from: String, to: String): ByteArray {
        val renamed = rewritten(zip) { name, bytes ->
            val text = String(bytes)
            when {
                name == "catalog.json" -> {
                    val manifest = objectMapper.readTree(text) as tools.jackson.databind.node.ObjectNode
                    manifest.get("catalogDependencies").forEach { (it as tools.jackson.databind.node.ObjectNode).takeIf { d -> d.get("catalogKey").asString() == from }?.put("alias", to) }
                    manifest.get("dependencies")?.forEach { (it as tools.jackson.databind.node.ObjectNode).takeIf { d -> d.get("catalogKey").asString() == from }?.put("catalogKey", to) }
                    objectMapper.writeValueAsString(manifest).toByteArray()
                }

                name.endsWith(".json") -> text.replace("\"catalogKey\":\"$from\"", "\"catalogKey\":\"$to\"").toByteArray()

                else -> bytes
            }
        }
        val fingerprint = CatalogArchiveReader.read(ByteArrayInputStream(renamed)).archive!!.use {
            app.epistola.catalog.canonical.CatalogCanonicalizer.currentQualifiedFingerprint(it).toString()
        }
        return rewritten(renamed) { name, bytes ->
            if (name != "catalog.json") return@rewritten bytes
            val manifest = objectMapper.readTree(bytes) as tools.jackson.databind.node.ObjectNode
            (manifest.get("release") as tools.jackson.databind.node.ObjectNode).put("fingerprint", fingerprint)
            objectMapper.writeValueAsBytes(manifest)
        }
    }

    private fun rewritten(zip: ByteArray, change: (String, ByteArray) -> ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { writer ->
            for ((name, bytes) in entries(zip)) {
                writer.putNextEntry(java.util.zip.ZipEntry(name))
                writer.write(change(name, bytes))
                writer.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
