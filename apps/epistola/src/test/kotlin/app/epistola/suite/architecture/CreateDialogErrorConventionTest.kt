package app.epistola.suite.architecture

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.test.assertTrue

/**
 * Enforces the create-dialog error-handling convention (see [ADR 0008]).
 *
 * Every create dialog (`templates/<entity>/new.html`) must:
 *  1. include the shared general error region and declare it via the `X-Epistola-Error-Region`
 *     header (so a thrown, non-field error renders inside the modal), AND
 *  2. give **every user-editable field its own inline error span**, so a handler-keyed field
 *     error can never silently vanish — the missing-span fragility ADR 0008 calls out (a handler
 *     adds `errors["catalog"]` but no `#…-error-catalog` span exists → the message is dropped).
 *
 * The per-field span carries `data-error=${errors?.containsKey('<field>')}`, so we assert each
 * field name appears in a `containsKey('<field>')` somewhere in the template.
 *
 * Controls that are not field-keyable need no span: file inputs, submit/button/hidden inputs and
 * radio/checkbox choice groups are excluded by `type`; the few remaining non-payload controls are
 * listed in [exemptFields] with their reason.
 */
class CreateDialogErrorConventionTest {

    private val templatesDir = Paths.get("src/main/resources/templates")

    private val nonFieldInputTypes =
        setOf("hidden", "submit", "button", "reset", "file", "radio", "checkbox", "image")

    /** Named controls that are NOT part of the create payload and so cannot produce a field error. */
    private val exemptFields = mapOf(
        // The data-example picker only drives the cascade pre-fill of testData; its value is never
        // submitted to StartLoadTest, so it cannot produce a field error.
        "loadtest/new.html" to setOf("exampleId"),
        // Repeating font-face row inputs — face/file problems are operational (thrown to the shared
        // region), not field-keyable.
        "fonts/new.html" to setOf("weight", "italic"),
    )

    private val fieldTagRegex = Regex("""<(input|select|textarea)\b[\s\S]*?>""", RegexOption.IGNORE_CASE)
    private val typeRegex = Regex("""\btype\s*=\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
    private val nameRegex = Regex("""\bname\s*=\s*"([^"]+)"""", RegexOption.IGNORE_CASE)

    private fun createDialogs(): List<Path> = Files.walk(templatesDir).use { stream ->
        stream.filter { it.name == "new.html" }.sorted().toList()
    }

    @Test
    fun `every create dialog declares the shared general error region`() {
        val violations = createDialogs().mapNotNull { path ->
            val text = path.readText()
            val missing = buildList {
                if (!text.contains("generalError")) add("the #dialog-error region (fragments/dialog :: generalError)")
                if (!text.contains("X-Epistola-Error-Region")) add("the X-Epistola-Error-Region hx-headers declaration")
            }
            if (missing.isEmpty()) null else "${templatesDir.relativize(path)} is missing ${missing.joinToString(" and ")}"
        }
        assertTrue(
            violations.isEmpty(),
            "Create dialogs without a shared general error region:\n${violations.joinToString("\n")}",
        )
    }

    @Test
    fun `every create-dialog field has an inline error span`() {
        val violations = mutableListOf<String>()
        for (path in createDialogs()) {
            val rel = templatesDir.relativize(path).toString()
            val text = path.readText()
            val exempt = exemptFields[rel].orEmpty()
            val fields = fieldTagRegex.findAll(text).mapNotNull { match ->
                val tag = match.value
                val isInput = tag.startsWith("<input", ignoreCase = true)
                val type = typeRegex.find(tag)?.groupValues?.get(1)?.lowercase() ?: if (isInput) "text" else "control"
                if (isInput && type in nonFieldInputTypes) return@mapNotNull null
                nameRegex.find(tag)?.groupValues?.get(1)
            }.toSet()

            for (field in fields - exempt) {
                if (!text.contains("containsKey('$field')")) {
                    violations.add(
                        "$rel: field \"$field\" has no inline error span " +
                            "(expected a .form-error with data-error=\${errors?.containsKey('$field')})",
                    )
                }
            }
        }
        assertTrue(
            violations.isEmpty(),
            "Create-dialog fields missing a per-field error span (ADR 0008 convention):\n" +
                violations.joinToString("\n"),
        )
    }
}
