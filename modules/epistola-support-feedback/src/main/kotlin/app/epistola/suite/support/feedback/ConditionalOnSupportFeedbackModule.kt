package app.epistola.suite.support.feedback

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty

@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@ConditionalOnProperty(
    prefix = "epistola.modules.support-feedback",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
annotation class ConditionalOnSupportFeedbackModule
