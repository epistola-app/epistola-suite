package app.epistola.suite.templates

import java.time.LocalDateTime

data class DocumentTemplate(
    val id: Long,
    val name: String,
    val lastModified: LocalDateTime,
)
