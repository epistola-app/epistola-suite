package app.epistola.suite.templates

import app.epistola.suite.templates.model.DataExample
import app.epistola.suite.templates.model.TemplateModel
import org.jdbi.v3.json.Json
import tools.jackson.databind.node.ObjectNode
import java.time.OffsetDateTime

/**
 * Document template entity.
 */
data class DocumentTemplate(
    val id: Long,
    val tenantId: Long,
    val name: String,
    @Json val templateModel: TemplateModel?,
    @Json val dataModel: ObjectNode? = null,
    @Json val dataExamples: List<DataExample> = emptyList(),
    val createdAt: OffsetDateTime,
    val lastModified: OffsetDateTime,
)
