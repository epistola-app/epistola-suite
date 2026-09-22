// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.relocation

import app.epistola.suite.assets.AssetMediaType
import app.epistola.suite.assets.commands.UploadAsset
import app.epistola.suite.attributes.codelists.commands.CreateCodeList
import app.epistola.suite.attributes.codelists.model.CodeListEntry
import app.epistola.suite.attributes.codelists.model.CodeListSource
import app.epistola.suite.attributes.commands.CreateAttributeDefinition
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.catalog.graph.CatalogResourceType
import app.epistola.suite.catalog.graph.ResourceAddress
import app.epistola.suite.common.ids.AssetKey
import app.epistola.suite.common.ids.AttributeId
import app.epistola.suite.common.ids.AttributeKey
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CodeListId
import app.epistola.suite.common.ids.CodeListKey
import app.epistola.suite.common.ids.ResourceIdentity
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
import app.epistola.suite.common.ids.VersionId
import app.epistola.suite.common.ids.VersionKey
import app.epistola.suite.documents.queries.PreviewDocument
import app.epistola.suite.fonts.commands.ImportFont
import app.epistola.suite.fonts.commands.ImportFontVariant
import app.epistola.suite.fonts.model.FontKind
import app.epistola.suite.fonts.model.FontVariantSource
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.stencils.commands.CreateStencil
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.versions.PublishVersion
import app.epistola.suite.templates.commands.versions.UpdateDraft
import app.epistola.suite.templates.model.Node
import app.epistola.suite.templates.model.Slot
import app.epistola.suite.templates.model.TemplateDocument
import app.epistola.suite.templates.model.ThemeRef
import app.epistola.suite.templates.queries.versions.GetDraft
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.withRequiredDataExample
import app.epistola.suite.themes.commands.CreateTheme
import app.epistola.template.model.ThemeRefOverride
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.io.ResourceLoader
import tools.jackson.databind.ObjectMapper

/**
 * What the relocation tests share: a tenant with a few authored catalogs, a way to create a
 * resource of any movable type at a chosen address, and the preview/move round trip.
 *
 * Every piece of state is created through production commands, as the ADR requires of relocation
 * tests; the SQL here only reads.
 */
abstract class RelocationTestSupport : IntegrationTestBase() {
    @Autowired
    protected lateinit var jdbi: Jdbi

    @Autowired
    protected lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var resourceLoader: ResourceLoader

    protected val letters: CatalogKey = CatalogKey.of("letters")
    protected val shared: CatalogKey = CatalogKey.of("shared")
    protected val archive: CatalogKey = CatalogKey.of("archive")

    /** A fresh tenant that has [catalogs] as authored catalogs besides `default`. */
    protected fun tenantWith(name: String, catalogs: List<CatalogKey> = listOf(letters, shared)): TenantKey {
        val tenant = createTenant(name).id
        withMediator { catalogs.forEach { CreateCatalog(tenant, it, it.value.replaceFirstChar(Char::uppercase)).execute() } }
        return tenant
    }

    protected fun catalogId(tenant: TenantKey, catalog: CatalogKey) = CatalogId(catalog, TenantId(tenant))

    protected fun preview(tenant: TenantKey, vararg relocations: ResourceRelocation): CatalogResourceMovePreview = preview(tenant, relocations.toList())

    protected fun preview(tenant: TenantKey, relocations: List<ResourceRelocation>): CatalogResourceMovePreview = withMediator { PreviewCatalogResourceMove(tenant, relocations).query() }

    /** Previews [relocations], requires the plan to be executable, and applies it. */
    protected fun move(tenant: TenantKey, vararg relocations: ResourceRelocation): CatalogResourceMovePreview = move(tenant, relocations.toList())

    protected fun move(tenant: TenantKey, relocations: List<ResourceRelocation>): CatalogResourceMovePreview {
        val plan = preview(tenant, relocations)
        assertThat(plan.blockers).describedAs("blockers for %s", relocations.map { it.source.id }).isEmpty()
        return withMediator { MoveCatalogResources(tenant, relocations, plan.planFingerprint).execute() }
    }

    /**
     * The identity registered at [address] right now, or null when nothing occupies it. A move leaves
     * nothing behind, so the old address answers null.
     */
    protected fun identityAt(tenant: TenantKey, address: ResourceAddress): ResourceIdentity? = jdbi.withHandle<ResourceIdentity?, Exception> { handle ->
        handle.createQuery(
            """
            SELECT resource_id FROM catalog_resources
            WHERE tenant_key = :tenantKey AND resource_type = :resourceType
              AND catalog_key = :catalogKey AND resource_key = :resourceKey
            """,
        )
            .bind("tenantKey", tenant)
            .bind("resourceType", address.type.wireName)
            .bind("catalogKey", address.catalogKey)
            .bind("resourceKey", address.key)
            .map { rs, _ -> ResourceIdentity.of(rs.getString("resource_id")) }
            .findOne()
            .orElse(null)
    }

    /**
     * Creates a resource of [movable]'s type at [catalog]/[key] and returns its address. An image's
     * key is the one given, uploaded with an explicit id, so every type can be placed exactly.
     */
    protected fun create(tenant: TenantKey, movable: MovableResource, catalog: CatalogKey, key: String): ResourceAddress = withMediator {
        val catalogId = catalogId(tenant, catalog)
        when (movable) {
            MovableResource.STENCIL -> CreateStencil(StencilId(StencilKey.of(key), catalogId), "Stencil $key").execute()
            MovableResource.ATTRIBUTE -> CreateAttributeDefinition(AttributeId(AttributeKey.of(key), catalogId), "Attribute $key").execute()
            MovableResource.TEMPLATE -> CreateDocumentTemplate(TemplateId(TemplateKey.of(key), catalogId), "Template $key").execute()
            MovableResource.CODE_LIST -> CreateCodeList(
                CodeListId(CodeListKey.of(key), catalogId),
                displayName = "Code list $key",
                sourceType = CodeListSource.INLINE,
                // An inline code list must have at least one entry.
                entries = listOf(CodeListEntry("nl", "Nederlands"), CodeListEntry("en", "English")),
            ).execute()
            MovableResource.ASSET -> uploadPng(tenant, catalog, key)
            MovableResource.FONT -> importFont(tenant, catalog, key)
            MovableResource.THEME -> CreateTheme(ThemeId(ThemeKey.of(key), catalogId), "Theme $key").execute()
        }
        ResourceAddress(movable.type, catalog.value, key)
    }

    protected fun uploadPng(tenant: TenantKey, catalog: CatalogKey, key: String? = null, content: ByteArray = PNG_1X1): AssetKey = withMediator {
        UploadAsset(
            tenantId = tenant,
            name = "${key ?: "image"}.png",
            mediaType = AssetMediaType.PNG,
            content = content,
            width = 1,
            height = 1,
            catalogKey = catalog,
            id = key?.let(AssetKey::of),
        ).execute().id
    }

    /** A one-face font family at [catalog]/[slug], its face backed by an asset in the same catalog. */
    protected fun importFont(tenant: TenantKey, catalog: CatalogKey, slug: String) = withMediator {
        val face = UploadAsset(
            tenantId = tenant,
            name = "$slug-regular.ttf",
            mediaType = AssetMediaType.TTF,
            content = ttfBytes(),
            width = null,
            height = null,
            catalogKey = catalog,
        ).execute().id
        ImportFont(
            tenantId = TenantId(tenant),
            catalogKey = catalog,
            slug = slug,
            name = "Font $slug",
            kind = FontKind.SANS.wire,
            variants = listOf(ImportFontVariant(400, false, FontVariantSource.ASSET, assetKey = face)),
        ).execute()
    }

    protected fun ttfBytes(): ByteArray = resourceLoader.getResource("classpath:epistola/fonts/inter/inter-Regular.ttf").contentAsByteArray

    /** A template model inserting stencil [stencilKey], qualified with [catalogKey] when given. */
    protected fun templateEmbedding(stencilKey: String, catalogKey: String? = null, nodeId: String = "stencil-instance"): TemplateDocument = templateEmbedding(listOf(Triple(nodeId, stencilKey, catalogKey)))

    /** A template model inserting every `(nodeId, stencilKey, catalogKey)` in [stencils]. */
    protected fun templateEmbedding(stencils: List<Triple<String, String, String?>>): TemplateDocument = TemplateDocument(
        modelVersion = 1,
        root = "root",
        nodes = mapOf("root" to Node(id = "root", type = "root", slots = listOf("children"))) +
            stencils.associate { (nodeId, stencilKey, catalogKey) ->
                nodeId to Node(
                    id = nodeId,
                    type = "stencil",
                    props = buildMap {
                        put("stencilId", stencilKey)
                        put("version", 1)
                        catalogKey?.let { put("catalogKey", it) }
                    },
                )
            },
        slots = mapOf("children" to Slot(id = "children", nodeId = "root", name = "children", children = stencils.map { it.first })),
        themeRef = ThemeRef.Inherit,
    )

    /** The default variant of template [key] in [catalog]. */
    protected fun templateVariant(tenant: TenantKey, catalog: CatalogKey, key: String = "invoice") = VariantId(VariantKey.INITIAL, TemplateId(TemplateKey.of(key), catalogId(tenant, catalog)))

    /**
     * Creates template [key] in [catalog], runs [setup] with its id, then publishes [model] as its
     * first version and returns that version.
     */
    protected fun publishTemplate(
        tenant: TenantKey,
        catalog: CatalogKey,
        model: TemplateDocument,
        key: String = "invoice",
        setup: (TemplateId) -> Unit = {},
    ): VersionKey {
        val variant = templateVariant(tenant, catalog, key)
        return withMediator {
            CreateDocumentTemplate(variant.templateId, "Template $key").execute().withRequiredDataExample()
            setup(variant.templateId)
            UpdateDraft(variant, model).execute()
            val draft = GetDraft(variant).query()!!.id
            PublishVersion(VersionId(draft, variant)).execute()
            draft
        }
    }

    /**
     * Renders published [version] of template [key] at [catalog] through the preview path: the real
     * renderer, including the font integrity check a published version runs first. Integration
     * tests wire a fake generation executor, so the generation pipeline would render nothing.
     */
    protected fun assertPreviewRenders(tenant: TenantKey, catalog: CatalogKey, version: VersionKey, key: String = "invoice") {
        val pdf = withMediator {
            PreviewDocument(
                tenantId = tenant,
                catalogKey = catalog,
                templateId = TemplateKey.of(key),
                data = objectMapper.createObjectNode(),
                variantId = VariantKey.INITIAL,
                versionId = version,
            ).query()
        }
        assertThat(pdf.take(4).toByteArray()).describedAs("a PDF").isEqualTo("%PDF".toByteArray())
    }

    /** Styles naming font [slug], in [catalog] or relatively when it is null. */
    protected fun fontStyle(slug: String, catalog: String?): Map<String, Any> = mapOf(
        "fontFamily" to buildMap {
            put("slug", slug)
            catalog?.let { put("catalogKey", it) }
        },
    )

    /** A template holding one text node, rendering with [themeRef]. */
    protected fun textModel(themeRef: ThemeRef = ThemeRef.Inherit): TemplateDocument = singleNodeModel(Node(id = "title", type = "text"), themeRef)

    /** A template holding just [node]. */
    protected fun singleNodeModel(node: Node, themeRef: ThemeRef = ThemeRef.Inherit): TemplateDocument = TemplateDocument(
        modelVersion = 1,
        root = "root",
        nodes = mapOf("root" to Node(id = "root", type = "root", slots = listOf("children")), node.id to node),
        slots = mapOf("children" to Slot(id = "children", nodeId = "root", name = "children", children = listOf(node.id))),
        themeRef = themeRef,
    )

    protected fun unzipText(bytes: ByteArray): Map<String, String> = buildMap {
        java.util.zip.ZipInputStream(bytes.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) put(entry.name, String(zip.readAllBytes(), Charsets.UTF_8))
                entry = zip.nextEntry
            }
        }
    }

    protected fun usesTheme(themeKey: String, catalogKey: String): TemplateDocument = TemplateDocument(
        modelVersion = 1,
        root = "root",
        nodes = mapOf("root" to Node(id = "root", type = "root")),
        slots = emptyMap(),
        themeRef = ThemeRefOverride(themeId = themeKey, catalogKey = catalogKey),
    )

    protected fun emptyTemplate(): TemplateDocument = TemplateDocument(
        modelVersion = 1,
        root = "root",
        nodes = mapOf("root" to Node(id = "root", type = "root")),
        slots = emptyMap(),
        themeRef = ThemeRef.Inherit,
    )

    protected companion object {
        /** The PNG signature: enough for an asset row, never decoded. */
        val PNG_1X1 = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)

        /** A real, decodable 1x1 PNG, for content a render has to draw. */
        fun renderablePng(): ByteArray = java.io.ByteArrayOutputStream().also { out ->
            javax.imageio.ImageIO.write(java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", out)
        }.toByteArray()

        fun address(type: CatalogResourceType, catalog: CatalogKey, key: String) = ResourceAddress(type, catalog.value, key)
    }
}
