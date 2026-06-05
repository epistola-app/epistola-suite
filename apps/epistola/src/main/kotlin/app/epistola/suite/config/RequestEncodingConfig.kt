package app.epistola.suite.config

import jakarta.servlet.ServletContext
import org.springframework.boot.web.servlet.ServletContextInitializer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.nio.charset.StandardCharsets

/**
 * Pins the servlet container's default request/response character encoding to
 * UTF-8 at the **context** level — established before any filter or servlet runs.
 *
 * This is the durable fix for the form-field mojibake class of bug. A servlet
 * `application/x-www-form-urlencoded` body is parsed lazily on the first
 * `getParameter()` call, using whatever `request.getCharacterEncoding()` is at
 * that moment; if it is still unset the container falls back to ISO-8859-1, and
 * once parsed the choice is locked. Spring's `CharacterEncodingFilter` only helps
 * if it runs before that first parse — a guarantee that evaporates the moment any
 * filter at the same precedence reads a parameter first (exactly what
 * `PopupLoginFilter` did, mangling diacritics like `Café` into `CafÃ©`).
 *
 * Setting the encoding on the [ServletContext] removes that ordering dependency
 * outright: the UTF-8 default is in place before request handling begins, so the
 * very first `getParameter()` — in any filter, at any `@Order` — decodes as UTF-8.
 * No future early-parameter-reading filter can reintroduce the bug. The
 * `CharacterEncodingFilter` and the `PopupLoginFilter` path guard remain as
 * complementary, belt-and-braces layers.
 */
@Configuration
class RequestEncodingConfig {

    @Bean
    fun utf8RequestEncodingInitializer(): ServletContextInitializer = ServletContextInitializer { servletContext: ServletContext ->
        servletContext.requestCharacterEncoding = StandardCharsets.UTF_8.name()
        servletContext.responseCharacterEncoding = StandardCharsets.UTF_8.name()
    }
}
