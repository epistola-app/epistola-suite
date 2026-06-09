package app.epistola.suite.support.snapshots

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty

@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@ConditionalOnProperty(
    prefix = "epistola.modules.support-snapshots",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
annotation class ConditionalOnSupportSnapshotsModule
