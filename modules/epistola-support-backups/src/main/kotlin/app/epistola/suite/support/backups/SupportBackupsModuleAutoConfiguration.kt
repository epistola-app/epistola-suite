package app.epistola.suite.support.backups

import app.epistola.suite.backups.BackupScheduler
import app.epistola.suite.support.backups.ui.BackupsHandler
import app.epistola.suite.support.backups.ui.BackupsNavContributor
import app.epistola.suite.support.backups.ui.BackupsRoutes
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.context.annotation.Import

@AutoConfiguration
@ConditionalOnSupportBackupsModule
@Import(
    BackupScheduler::class,
    BackupsHandler::class,
    BackupsRoutes::class,
    BackupsNavContributor::class,
)
class SupportBackupsModuleAutoConfiguration
