package app.epistola.suite.handlers

import app.epistola.suite.htmx.page
import app.epistola.suite.htmx.tenantId
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.security.SecurityContext
import app.epistola.suite.templates.commands.ImportTemplates
import app.epistola.suite.templates.queries.ExportTemplates
import jakarta.servlet.http.Part
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse
import tools.jackson.databind.ObjectMapper
import java.time.LocalDate

@Component
class AdminHandler(
    private val objectMapper: ObjectMapper,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun dataManagement(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        requireAdmin(tenantId.key)

        return ServerResponse.ok().page("admin/data-management") {
            "pageTitle" to "Data Management - Epistola"
            "tenantId" to tenantId.key
            "activeNavSection" to "admin"
        }
    }

    fun exportTemplates(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        requireAdmin(tenantId.key)

        val result = ExportTemplates(tenantId).query()
        val json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(result.templates)
        val filename = "epistola-templates-${tenantId.key}-${LocalDate.now()}.json"

        return ServerResponse.ok()
            .contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$filename\"")
            .body(json)
    }

    fun importTemplates(request: ServerRequest): ServerResponse {
        val tenantId = request.tenantId()
        requireAdmin(tenantId.key)

        val multipartData = request.multipartData()
        val filePart: Part = multipartData["file"]?.firstOrNull()
            ?: return ServerResponse.ok().page("admin/data-management") {
                "pageTitle" to "Data Management - Epistola"
                "tenantId" to tenantId.key
                "activeNavSection" to "admin"
                "importError" to "No file provided"
            }

        return try {
            val contentBytes = filePart.inputStream.use { it.readAllBytes() }
            val templates: List<app.epistola.suite.templates.commands.ImportTemplateInput> = objectMapper.readValue(
                contentBytes,
                objectMapper.typeFactory.constructCollectionType(
                    List::class.java,
                    app.epistola.suite.templates.commands.ImportTemplateInput::class.java,
                ),
            )

            val results = ImportTemplates(tenantId = tenantId, templates = templates).execute()

            val created = results.count { it.status == app.epistola.suite.templates.commands.ImportStatus.CREATED }
            val updated = results.count { it.status == app.epistola.suite.templates.commands.ImportStatus.UPDATED }
            val unchanged = results.count { it.status == app.epistola.suite.templates.commands.ImportStatus.UNCHANGED }
            val failed = results.count { it.status == app.epistola.suite.templates.commands.ImportStatus.FAILED }

            val summary = mapOf<String, Any>(
                "total" to results.size,
                "created" to created,
                "updated" to updated,
                "unchanged" to unchanged,
                "failed" to failed,
            )

            ServerResponse.ok().page("admin/data-management") {
                "pageTitle" to "Data Management - Epistola"
                "tenantId" to tenantId.key
                "activeNavSection" to "admin"
                "importResults" to results
                "importSummary" to summary
            }
        } catch (e: Exception) {
            logger.error("Failed to import templates: ${e.message}", e)
            ServerResponse.ok().page("admin/data-management") {
                "pageTitle" to "Data Management - Epistola"
                "tenantId" to tenantId.key
                "activeNavSection" to "admin"
                "importError" to (e.message ?: "Unknown error during import")
            }
        }
    }

    private fun requireAdmin(tenantKey: app.epistola.suite.common.ids.TenantKey) {
        val principal = SecurityContext.current()
        if (!principal.isAdmin(tenantKey)) {
            throw org.springframework.security.access.AccessDeniedException("Admin access required")
        }
    }
}
