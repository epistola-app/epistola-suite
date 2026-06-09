package app.epistola.suite.support.backups

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty

@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@ConditionalOnProperty(
    prefix = "epistola.modules.support-backups",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
annotation class ConditionalOnSupportBackupsModule
