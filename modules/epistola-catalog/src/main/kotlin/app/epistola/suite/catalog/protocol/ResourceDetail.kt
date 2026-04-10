package app.epistola.suite.catalog.protocol

import app.epistola.suite.templates.model.DocumentStyles
import app.epistola.suite.templates.model.PageSettings
import app.epistola.suite.templates.model.TemplateDocument
import app.epistola.suite.themes.BlockStylePresets
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import tools.jackson.databind.node.ObjectNode

/**
 * Wire format for a single resource detail JSON fetched from a catalog's detailUrl.
 * Matches the schema defined in docs/exchange.md.
 */
data class ResourceDetail(
    val schemaVersion: Int,
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "type")
    @JsonSubTypes(
        JsonSubTypes.Type(value = TemplateResource::class, name = "template"),
        JsonSubTypes.Type(value = ThemeResource::class, name = "theme"),
        JsonSubTypes.Type(value = StencilResource::class, name = "stencil"),
        JsonSubTypes.Type(value = AttributeResource::class, name = "attribute"),
        JsonSubTypes.Type(value = AssetResource::class, name = "asset"),
    )
    val resource: CatalogResource,
)

/**
 * Base type for all catalog resources. Discriminated by the `type` field.
 */
sealed interface CatalogResource {
    val type: String
    val slug: String
    val name: String
}

data class TemplateResource(
    override val slug: String,
    override val name: String,
    val dataModel: ObjectNode? = null,
    val dataExamples: List<DataExampleEntry>? = null,
    val templateModel: TemplateDocument,
    val variants: List<VariantEntry>,
) : CatalogResource {
    override val type: String get() = "template"
}

data class ThemeResource(
    override val slug: String,
    override val name: String,
    val description: String? = null,
    val documentStyles: DocumentStyles = emptyMap(),
    val pageSettings: PageSettings? = null,
    val blockStylePresets: BlockStylePresets? = null,
    val spacingUnit: Float? = null,
) : CatalogResource {
    override val type: String get() = "theme"
}

data class StencilResource(
    override val slug: String,
    override val name: String,
    val description: String? = null,
    val tags: List<String> = emptyList(),
    val content: TemplateDocument,
) : CatalogResource {
    override val type: String get() = "stencil"
}

data class AttributeResource(
    override val slug: String,
    override val name: String,
    val allowedValues: List<String> = emptyList(),
) : CatalogResource {
    override val type: String get() = "attribute"
}

data class AssetResource(
    override val slug: String,
    override val name: String,
    val mediaType: String,
    val width: Int? = null,
    val height: Int? = null,
    val contentUrl: String,
) : CatalogResource {
    override val type: String get() = "asset"
}

data class DataExampleEntry(
    val name: String,
    val data: ObjectNode,
)

data class VariantEntry(
    val id: String,
    val title: String? = null,
    val attributes: Map<String, String>? = null,
    val templateModel: TemplateDocument? = null,
    val isDefault: Boolean = false,
)
