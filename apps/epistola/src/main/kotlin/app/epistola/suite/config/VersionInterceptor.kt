package app.epistola.suite.config

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.info.BuildProperties
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.ModelAndView

/**
 * Injects build version information into every Thymeleaf model.
 *
 * Uses a [HandlerInterceptor] instead of `@ControllerAdvice`/`@ModelAttribute`
 * because the UI layer uses functional routing exclusively — `@ModelAttribute`
 * only applies to annotated `@Controller` beans, not functional route handlers.
 */
@Component
class VersionInterceptor(private val buildProperties: BuildProperties?) : HandlerInterceptor {

    override fun postHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
        modelAndView: ModelAndView?,
    ) {
        if (modelAndView == null) return
        modelAndView.addObject("appVersion", buildProperties?.version ?: "dev")
        modelAndView.addObject("appName", buildProperties?.name ?: "Epistola")
    }
}
