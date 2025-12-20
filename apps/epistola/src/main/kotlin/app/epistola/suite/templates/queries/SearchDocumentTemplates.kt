package app.epistola.suite.templates.queries

import app.epistola.suite.templates.DocumentTemplate
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.kotlin.mapTo
import org.springframework.stereotype.Component

data class SearchDocumentTemplates(
    val searchTerm: String? = null,
)

@Component
class SearchDocumentTemplatesHandler(private val jdbi: Jdbi) {
    fun handle(query: SearchDocumentTemplates): List<DocumentTemplate> {
        return jdbi.withHandle<List<DocumentTemplate>, Exception> { handle ->
            val sql = buildString {
                append("SELECT * FROM document_templates WHERE 1=1")
                if (!query.searchTerm.isNullOrBlank()) {
                    append(" AND name ILIKE :searchTerm")
                }
                append(" ORDER BY last_modified DESC")
            }

            val jdbiQuery = handle.createQuery(sql)
            if (!query.searchTerm.isNullOrBlank()) {
                jdbiQuery.bind("searchTerm", "%${query.searchTerm}%")
            }
            jdbiQuery.mapTo<DocumentTemplate>().list()
        }
    }
}