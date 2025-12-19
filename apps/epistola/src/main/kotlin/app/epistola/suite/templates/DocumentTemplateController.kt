package app.epistola.suite.templates

import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import java.time.LocalDateTime

@Controller
@RequestMapping("/templates")
class DocumentTemplateController {
    @GetMapping
    fun list(model: Model): String {
        val templates = listOf(
            DocumentTemplate(
                id = 1,
                name = "Invoice Template",
                lastModified = LocalDateTime.of(2025, 1, 15, 10, 30),
            ),
            DocumentTemplate(
                id = 2,
                name = "Contract Template",
                lastModified = LocalDateTime.of(2025, 1, 10, 14, 45),
            ),
            DocumentTemplate(
                id = 3,
                name = "Letter Template",
                lastModified = LocalDateTime.of(2025, 1, 5, 9, 0),
            ),
        )

        model.addAttribute("templates", templates)
        return "templates/list"
    }
}
