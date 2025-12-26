package app.epistola.suite.config

import org.springframework.boot.info.BuildProperties
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ModelAttribute

/**
 * Exposes build version information to all Thymeleaf templates.
 */
@ControllerAdvice
class VersionConfig(private val buildProperties: BuildProperties) {

    @ModelAttribute("appVersion")
    fun appVersion(): String = buildProperties.version ?: "unknown"

    @ModelAttribute("appName")
    fun appName(): String = buildProperties.name ?: "Epistola"
}
