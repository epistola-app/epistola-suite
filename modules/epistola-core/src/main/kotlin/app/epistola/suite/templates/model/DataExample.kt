package app.epistola.suite.templates.model

import tools.jackson.core.JsonGenerator
import tools.jackson.core.JsonParser
import tools.jackson.databind.DeserializationContext
import tools.jackson.databind.SerializationContext
import tools.jackson.databind.ValueDeserializer
import tools.jackson.databind.ValueSerializer
import tools.jackson.databind.annotation.JsonDeserialize
import tools.jackson.databind.annotation.JsonPOJOBuilder
import tools.jackson.databind.annotation.JsonSerialize
import tools.jackson.databind.node.ObjectNode
import java.util.UUID

/**
 * A named example data set that can be validated against the template's dataModel.
 * Used for previewing templates with sample data during editing.
 */
@JsonDeserialize(builder = DataExample.Builder::class)
data class DataExample(
    val id: String,
    val name: String,
    val data: ObjectNode,
) {
    @JsonPOJOBuilder(withPrefix = "with", buildMethodName = "build")
    class Builder {
        var id: String? = null
        var name: String? = null
        var data: ObjectNode? = null

        fun withId(id: String?) = apply { this.id = id }
        fun withName(name: String?) = apply { this.name = name }
        fun withData(data: ObjectNode?) = apply { this.data = data }

        fun build() = DataExample(
            id = id ?: UUID.randomUUID().toString(),
            name = name ?: "Unnamed",
            data = data ?: throw IllegalArgumentException("data is required and cannot be null"),
        )
    }
}

/**
 * Wrapper type for a list of [DataExample] to work around Java type erasure.
 *
 * When Jackson deserializes a generic `List<DataExample>` from JSON, type erasure
 * causes it to produce `List<LinkedHashMap>` instead. This wrapper class provides
 * explicit type information that Jackson can use for proper deserialization.
 *
 * The database stores this as a JSON array `[{...}, {...}]`, and this class
 * serializes/deserializes directly from/to that format using custom serializers.
 *
 * Usage: Replace `@Json val dataExamples: List<DataExample>` with
 * `@Json val dataExamples: DataExamples` in entity classes.
 */
@JsonDeserialize(using = DataExamplesDeserializer::class)
@JsonSerialize(using = DataExamplesSerializer::class)
data class DataExamples(
    private val items: List<DataExample> = emptyList(),
) : List<DataExample> by items {
    companion object {
        val EMPTY = DataExamples(emptyList())

        fun of(vararg examples: DataExample) = DataExamples(examples.toList())
    }
}

/**
 * Custom Jackson deserializer for [DataExamples] that reads a JSON array directly.
 */
class DataExamplesDeserializer : ValueDeserializer<DataExamples>() {
    override fun deserialize(
        p: JsonParser,
        ctxt: DeserializationContext,
    ): DataExamples {
        val items: List<DataExample> = ctxt.readValue(
            p,
            ctxt.typeFactory.constructCollectionType(List::class.java, DataExample::class.java),
        )
        return DataExamples(items)
    }
}

/**
 * Custom Jackson serializer for [DataExamples] that writes a JSON array directly.
 */
class DataExamplesSerializer : ValueSerializer<DataExamples>() {
    override fun serialize(
        value: DataExamples,
        gen: JsonGenerator,
        ctxt: SerializationContext,
    ) {
        gen.writeStartArray()
        for (example in value) {
            ctxt.writeValue(gen, example)
        }
        gen.writeEndArray()
    }
}
