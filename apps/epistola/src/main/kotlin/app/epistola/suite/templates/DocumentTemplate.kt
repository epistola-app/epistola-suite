package app.epistola.suite.templates

import app.epistola.suite.templates.model.EditorTemplate
import org.jdbi.v3.json.Json
import java.time.OffsetDateTime

data class DocumentTemplate(
    val id: Long,
    val name: String,
    @Json val content: EditorTemplate?,
    val createdAt: OffsetDateTime,
    val lastModified: OffsetDateTime,
)
