package app.epistola.suite.templates.model

import tools.jackson.databind.annotation.JsonDeserialize
import tools.jackson.databind.annotation.JsonPOJOBuilder
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
