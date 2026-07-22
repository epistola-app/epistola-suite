package app.epistola.suite.web

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.thymeleaf.context.Context
import org.thymeleaf.spring6.SpringTemplateEngine
import org.thymeleaf.templatemode.TemplateMode
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver

/**
 * Proves the shared dialog shell (`epistola-web/dialog :: dialog-shell`) wraps
 * all three acceptance shapes: a plain field form, a multipart upload form, and
 * the formless api-key "reveal" panel. The caller owns the <form> (or formless
 * block) inside the shell's `content` slot, so arbitrary form-level attributes
 * (e.g. data-radio-panes) must survive — shape (a) asserts exactly that.
 *
 * Renders the real classpath templates (the shell, the optional body/footer
 * helpers, and the embedded `epistola-web/form-error` slot) through a standalone
 * Thymeleaf engine — no Spring context or database, so it runs as a fast unit
 * test. The shell uses only plain variable / fragment / standard-object
 * expressions, which evaluate identically here and under the app's
 * SpringTemplateEngine.
 */
class DialogSkeletonFragmentTest {

    private val engine = SpringTemplateEngine().apply {
        addTemplateResolver(
            ClassLoaderTemplateResolver().apply {
                prefix = "templates/"
                suffix = ".html"
                templateMode = TemplateMode.HTML
                characterEncoding = "UTF-8"
                isCacheable = false
            },
        )
    }

    // Strip HTML comments: the skeleton's explanatory comments legitimately
    // mention "<form", so the negative assertions must run against rendered DOM,
    // not commentary.
    private val htmlComment = Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL)

    private fun render(fragment: String): String = engine.process("test/dialog-skeleton-cases", setOf(fragment), Context())
        .replace(htmlComment, "")

    @Test
    fun `shape a - plain field form renders form chrome with embedded error slot`() {
        val html = render("plain-case")

        // Chrome
        assertThat(html).contains("""<dialog id="plain-dialog"""")
        assertThat(html).contains("class=\"ep-dialog\"")
        assertThat(html).contains("ep-dialog-header")
        assertThat(html).contains("New Thing")
        assertThat(html).contains("ep-dialog-body")
        assertThat(html).contains("ep-dialog-footer")
        // Caller-owned form + attributes
        assertThat(html).contains("<form")
        assertThat(html).contains("""hx-post="/tenants/acme/things"""")
        assertThat(html).contains("""hx-target="#thing-list"""")
        assertThat(html).contains("""hx-swap="outerHTML"""")
        // Arbitrary form-level attributes survive (the reason the shell no
        // longer emits the form): a data-* hook and a data-testid.
        assertThat(html).contains("""data-radio-panes="sourceType"""")
        assertThat(html).contains("""data-testid="thing-create-form"""")
        assertThat(html).doesNotContain("multipart/form-data")
        // form-error slot embedded INSIDE the dialog
        assertThat(html).contains("""id="plain-error"""")
        assertThat(html).contains("data-form-error")
        // Caller-supplied body/footer content
        assertThat(html).contains("""name="name"""")
        assertThat(html).contains(">Create</button>")
        // closeUrl (URL-addressable convention) is emitted as data-close-url.
        assertThat(html).contains("""data-close-url="/tenants/acme/things"""")
        // No CSP-hostile output
        assertThat(html).doesNotContain("<script")
    }

    @Test
    fun `shape b - multipart upload form sets encoding and keeps error slot`() {
        val html = render("multipart-case")

        assertThat(html).contains("""<dialog id="upload-dialog"""")
        assertThat(html).contains("Upload Font")
        assertThat(html).contains("<form")
        assertThat(html).contains("""hx-encoding="multipart/form-data"""")
        assertThat(html).contains("""hx-post="/tenants/acme/fonts"""")
        assertThat(html).contains("""type="file"""")
        assertThat(html).contains("""id="upload-error"""")
        assertThat(html).contains("data-form-error")
        assertThat(html).doesNotContain("<script")
    }

    @Test
    fun `shape c - reveal panel renders no form and a single Close button`() {
        val html = render("reveal-case")

        assertThat(html).contains("""<dialog id="reveal-dialog"""")
        assertThat(html).contains("API key created")
        assertThat(html).contains("ep-dialog-body")
        assertThat(html).contains("ep-dialog-footer")
        // The reveal content and its Close button
        assertThat(html).contains("Copy this key now")
        assertThat(html).contains(">Close</button>")
        assertThat(html).contains("""data-close-dialog="reveal-dialog"""")
        // Formless: no <form> element and no form-error slot (errorId omitted)
        assertThat(html).doesNotContain("<form")
        assertThat(html).doesNotContain("data-form-error")
        // closeUrl omitted → th:attr removes the attribute entirely.
        assertThat(html).doesNotContain("data-close-url")
        assertThat(html).doesNotContain("<script")
    }
}
