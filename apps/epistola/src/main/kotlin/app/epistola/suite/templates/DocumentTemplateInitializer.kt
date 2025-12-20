package app.epistola.suite.templates

import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.CreateDocumentTemplateHandler
import org.jdbi.v3.core.Jdbi
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component

@Component
class DocumentTemplateInitializer(
    private val jdbi: Jdbi,
    private val createHandler: CreateDocumentTemplateHandler,
) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        val count = jdbi.withHandle<Long, Exception> { handle ->
            handle.createQuery("SELECT COUNT(*) FROM document_templates")
                .mapTo(Long::class.java)
                .one()
        }

        if (count == 0L) {
            log.info("Seeding document templates...")
            seedTemplates()
            log.info("Seeded {} document templates", SEED_TEMPLATES.size)
        } else {
            log.info("Found {} existing document templates, skipping seed", count)
        }
    }

    private fun seedTemplates() {
        SEED_TEMPLATES.forEach { name ->
            createHandler.handle(CreateDocumentTemplate(name))
        }
    }

    companion object {
        private val SEED_TEMPLATES = listOf(
            "Invoice Template",
            "Contract Template",
            "Letter Template",
            "Report Template",
            "Proposal Template",
        )
    }
}
