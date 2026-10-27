// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package db.migration.core.frozen

import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode
import java.math.BigDecimal

/**
 * The content the 2.0 upgrade stores for each 1.x resource (ADR 0033: one shape for every type,
 * `{ "resource": <its wire form>, … }`), frozen: how the columns of 1.x map to the wire form of
 * contract 2.0, as the suite serializes it. `fixtures/storage/v1.json` pins every envelope by its
 * content hash (`FrozenToolkitGoldenVectorsTest`), and the `*StorageConversion` checks of
 * `modules/testing` prove the suite stores every bundled and demo resource exactly so.
 */
object Envelopes {

    /** CRC32 of this file with this number written as 0; `CodeMigrationGuardTest` checks it. */
    const val CHECKSUM: Int = -1349305847

    private val mapper = CanonicalJson.mapper

    /**
     * A theme (`ThemeResource`): every field present, null where the column is, the spacing unit a
     * JSON float as the protocol's `Float` writes it.
     */
    fun theme(
        key: String,
        name: String,
        description: String?,
        documentStyles: JsonNode?,
        pageSettings: JsonNode?,
        blockStylePresets: JsonNode?,
        spacingUnit: BigDecimal?,
    ): ObjectNode = envelope(
        mapper.createObjectNode().apply {
            put("key", key)
            put("name", name)
            put("description", description)
            set("documentStyles", documentStyles ?: mapper.createObjectNode())
            set("pageSettings", pageSettings ?: nullNode())
            set("blockStylePresets", blockStylePresets ?: nullNode())
            if (spacingUnit == null) putNull("spacingUnit") else put("spacingUnit", spacingUnit.toFloat())
            put("type", "theme")
        },
    )

    /** One entry of a 1.x code list. */
    data class CodeListEntry(val code: String, val label: String, val sortOrder: Int, val hidden: Boolean)

    /**
     * A code list (`CodeListResource`): its entries in the order given, and beside it, for a list
     * with a source, the source the suite refreshes it from, its credential named by id.
     */
    fun codeList(
        key: String,
        name: String,
        description: String?,
        entries: List<CodeListEntry>,
        sourceType: String,
        sourceUrl: String?,
        authType: String,
        credentialId: String?,
    ): ObjectNode = envelope(
        mapper.createObjectNode().apply {
            put("key", key)
            put("name", name)
            put("description", description)
            set(
                "entries",
                mapper.createArrayNode().apply {
                    entries.forEach { entry ->
                        add(mapper.createObjectNode().put("code", entry.code).put("label", entry.label).put("sortOrder", entry.sortOrder).put("hidden", entry.hidden))
                    }
                },
            )
            put("type", "codeList")
        },
    ).apply {
        if (sourceType != "INLINE") {
            set("source", mapper.createObjectNode().put("type", sourceType).put("url", sourceUrl).put("auth", authType).put("credential", credentialId))
        }
    }

    /** An attribute (`AttributeResource`): `codeListBinding` null when unbound, its `catalogKey` null for a list of its own catalog. */
    fun attribute(key: String, name: String, allowedValues: JsonNode?, bindingAlias: String?, bindingKey: String?): ObjectNode = envelope(
        mapper.createObjectNode().apply {
            put("key", key)
            put("name", name)
            set("allowedValues", allowedValues ?: mapper.createArrayNode())
            if (bindingKey == null) {
                putNull("codeListBinding")
            } else {
                set("codeListBinding", mapper.createObjectNode().put("catalogKey", bindingAlias).put("key", bindingKey))
            }
            put("type", "attribute")
        },
    )

    /** One face of a font family as the wire form says it. */
    data class Face(val weight: Int, val italic: Boolean, val mediaType: String, val contentHash: String)

    /** A font (`FontResource`): its faces by italic, then weight. */
    fun font(key: String, name: String, kind: String, faces: List<Face>): ObjectNode = envelope(
        mapper.createObjectNode().apply {
            put("key", key)
            put("name", name)
            put("kind", kind)
            set(
                "variants",
                mapper.createArrayNode().also { variants ->
                    faces.sortedWith(compareBy({ it.italic }, { it.weight })).forEach { face ->
                        variants.add(
                            mapper.createObjectNode().put("weight", face.weight).put("italic", face.italic)
                                .put("mediaType", face.mediaType).put("contentHash", face.contentHash),
                        )
                    }
                },
            )
            put("type", "font")
        },
    )

    /** An image (`ImageResource`): width, height and hash null where the column is. */
    fun image(key: String, name: String, mediaType: String, width: Int?, height: Int?, contentHash: String?): ObjectNode = envelope(
        mapper.createObjectNode().apply {
            put("key", key)
            put("name", name)
            put("mediaType", mediaType)
            put("width", width)
            put("height", height)
            put("contentHash", contentHash)
            put("type", "image")
        },
    )

    /**
     * A stencil (`StencilResource`), of a stencil of catalog [catalogKey]: its document in released
     * form ([ReferenceWalker.releasedForm]), `readsTemplateData` only when true, as the wire form
     * leaves it out.
     */
    fun stencil(
        key: String,
        catalogKey: String,
        name: String,
        description: String?,
        tags: JsonNode?,
        content: JsonNode,
        parameterSchema: JsonNode?,
        readsTemplateData: Boolean,
    ): ObjectNode = envelope(
        mapper.createObjectNode().apply {
            put("key", key)
            put("name", name)
            put("description", description)
            set("tags", tags ?: mapper.createArrayNode())
            set("content", ReferenceWalker.releasedForm(content, catalogKey))
            set("parameterSchema", parameterSchema?.takeUnless { it.isNull } ?: nullNode())
            if (readsTemplateData) put("readsTemplateData", true)
            put("type", "stencil")
        },
    )

    /** A template's variant as its template's content names it. */
    data class VariantEntry(val key: String, val attributes: JsonNode?, val isDefault: Boolean)

    /**
     * A template's own content (`TemplateContents.head` of 2.0): its wire form without models, each
     * variant by key, attributes and whether it is the default; `description` only when it has one,
     * `themeCatalogKey` null for a theme of its own catalog.
     */
    fun template(
        key: String,
        name: String,
        description: String?,
        themeKey: String?,
        themeCatalogKey: String?,
        pdfaEnabled: Boolean,
        dataModel: JsonNode?,
        dataExamples: ArrayNode,
        variants: List<VariantEntry>,
    ): ObjectNode = envelope(
        mapper.createObjectNode().apply {
            put("key", key)
            put("name", name)
            put("themeKey", themeKey)
            put("themeCatalogKey", themeCatalogKey)
            put("pdfaEnabled", pdfaEnabled)
            set("dataModel", dataModel?.takeUnless { it.isNull } ?: nullNode())
            set("dataExamples", dataExamples)
            set(
                "variants",
                mapper.createArrayNode().also { entries ->
                    variants.forEach { variant ->
                        entries.add(
                            mapper.createObjectNode().apply {
                                put("key", variant.key)
                                set("attributes", variant.attributes?.takeUnless { it.isNull } ?: mapper.createObjectNode())
                                put("isDefault", variant.isDefault)
                            },
                        )
                    }
                },
            )
            if (description != null) put("description", description)
            put("type", "template")
        },
    )

    /**
     * A variant's own content (`TemplateContents.part` of 2.0, `VariantEntry` of contract
     * `2.0.0-alpha.2`): its title, its model as given, its metadata when it has some, and its
     * description when it has one that is not blank.
     */
    fun variant(title: String, model: JsonNode, metadata: JsonNode?, description: String?): ObjectNode = envelope(
        mapper.createObjectNode().apply {
            put("title", title)
            set("templateModel", model)
            metadata?.takeIf { it is ObjectNode && !it.isEmpty }?.let { set("metadata", it) }
            description?.takeIf { it.isNotBlank() }?.let { put("description", it) }
        },
    )

    private fun envelope(resource: ObjectNode): ObjectNode = mapper.createObjectNode().set("resource", resource)
}
