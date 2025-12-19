package app.epistola.suite.templates

import java.time.OffsetDateTime

data class DocumentTemplate(
    val id: Long,
    val name: String,
    val content: String?,
    val createdAt: OffsetDateTime,
    val lastModified: OffsetDateTime,
)
