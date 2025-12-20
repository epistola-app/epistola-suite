package app.epistola.suite.templates.queries

import app.epistola.suite.templates.DocumentTemplate
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

data class SearchDocumentTemplates(
    val name: String? = null,
)

@Component
class SearchDocumentTemplatesHandler(private val jdbi: Jdbi) {
    fun handle(query: SearchDocumentTemplates): List<DocumentTemplate> {
        return jdbi.withHandle<List<DocumentTemplate>, Exception> { handle ->
            val sql = buildString {
                append("SELECT * FROM document_templates WHERE 1=1")
                if (query.name != null) {
                    append(" AND name = :name")
                }
                append(" ORDER BY name")
            }

            val jdbiQuery = handle.createQuery(sql)
            if (query.name != null) {
                jdbiQuery.bind("name", query.name)
            }
            jdbiQuery.mapTo<DocumentTemplate>().list()
        }
    }
}