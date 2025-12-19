package app.epistola.suite.htmx

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.web.servlet.View
import org.springframework.web.servlet.ViewResolver
import org.thymeleaf.spring6.SpringTemplateEngine
import org.thymeleaf.spring6.view.ThymeleafViewResolver
import java.io.StringWriter
import java.util.Locale

/**
 * Custom View that renders multiple HTMX fragments into a single response.
 *
 * For Out-of-Band (OOB) fragments, wraps the content in a div with hx-swap-oob="true".
 * This allows updating multiple parts of the page in a single HTMX response.
 */
class HtmxFragmentsView(
    private val templateEngine: SpringTemplateEngine,
    private val fragments: List<HtmxFragment>,
) : View {

    override fun render(
        model: MutableMap<String, *>?,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ) {
        response.contentType = "text/html;charset=UTF-8"
        val writer = response.writer

        for (fragment in fragments) {
            val templatePath = if (fragment.fragmentName != null) {
                "${fragment.template} :: ${fragment.fragmentName}"
            } else {
                fragment.template
            }

            val context = org.thymeleaf.context.WebContext(
                org.thymeleaf.web.servlet.JakartaServletWebApplication
                    .buildApplication(request.servletContext)
                    .buildExchange(request, response),
                Locale.getDefault(),
                fragment.model,
            )

            val renderedFragment = templateEngine.process(templatePath, context)

            if (fragment.isOob) {
                // OOB fragments are written directly - they should already have hx-swap-oob in the template
                // or we wrap them if the fragment doesn't include the wrapper element
                writer.write(renderedFragment)
            } else {
                writer.write(renderedFragment)
            }
        }
    }

    companion object {
        const val VIEW_NAME = "htmx-fragments"
        const val FRAGMENTS_KEY = "_htmxFragments"
    }
}

/**
 * ViewResolver that handles the special HTMX fragments view.
 */
@Component
class HtmxFragmentsViewResolver(
    private val templateEngine: SpringTemplateEngine,
) : ViewResolver {

    override fun resolveViewName(viewName: String, locale: Locale): View? {
        if (viewName != HtmxFragmentsView.VIEW_NAME) {
            return null
        }
        // The actual fragments will be passed in the model
        // We return a placeholder view that will be populated during rendering
        return HtmxFragmentsPlaceholderView(templateEngine)
    }
}

/**
 * Placeholder view that extracts fragments from the model and delegates to HtmxFragmentsView.
 */
internal class HtmxFragmentsPlaceholderView(
    private val templateEngine: SpringTemplateEngine,
) : View {

    @Suppress("UNCHECKED_CAST")
    override fun render(
        model: MutableMap<String, *>?,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ) {
        val fragments = model?.get(HtmxFragmentsView.FRAGMENTS_KEY) as? List<HtmxFragment>
            ?: throw IllegalStateException("No fragments found in model")

        HtmxFragmentsView(templateEngine, fragments).render(model, request, response)
    }
}
