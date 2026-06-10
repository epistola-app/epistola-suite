package app.epistola.suite

import app.epistola.suite.backups.BackupScheduler
import app.epistola.suite.feedback.commands.CreateFeedbackHandler
import app.epistola.suite.snapshots.TenantSnapshotSyncService
import app.epistola.suite.support.SupportEntitlementService
import app.epistola.suite.support.ui.SupportOverviewHandler
import app.epistola.suite.testing.TestcontainersConfiguration
import app.epistola.suite.upgrading.UpgradingSnapshotScheduler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles

@Import(TestcontainersConfiguration::class)
@SpringBootTest(
    classes = [EpistolaSuiteApplication::class],
    properties = [
        "epistola.modules.support.enabled=false",
        "epistola.modules.support-feedback.enabled=false",
        "epistola.modules.support-backups.enabled=false",
        "epistola.modules.support-snapshots.enabled=false",
        "epistola.modules.support-upgrading.enabled=false",
    ],
)
@ActiveProfiles("test")
@Tag("integration")
class SupportModulesDisabledApplicationTests(
    private val context: ApplicationContext,
) {
    @Test
    fun supportModulesCanBeDisabledAsWholeModules() {
        assertThat(context.getBeanNamesForType(SupportEntitlementService::class.java)).isEmpty()
        assertThat(context.getBeanNamesForType(SupportOverviewHandler::class.java)).isEmpty()
        assertThat(context.getBeanNamesForType(CreateFeedbackHandler::class.java)).isEmpty()
        assertThat(context.getBeanNamesForType(BackupScheduler::class.java)).isEmpty()
        assertThat(context.getBeanNamesForType(TenantSnapshotSyncService::class.java)).isEmpty()
        assertThat(context.getBeanNamesForType(UpgradingSnapshotScheduler::class.java)).isEmpty()
    }
}
