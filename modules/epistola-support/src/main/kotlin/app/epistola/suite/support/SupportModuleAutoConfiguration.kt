package app.epistola.suite.support

import app.epistola.suite.support.ui.SupportNavContributor
import app.epistola.suite.support.ui.SupportOverviewHandler
import app.epistola.suite.support.ui.SupportOverviewRoutes
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Import

@AutoConfiguration
@ConditionalOnSupportModule
@EnableConfigurationProperties(SupportProperties::class, HubHealthCheckProperties::class)
@Import(
    SupportConfiguration::class,
    HubConnectivityService::class,
    HubHealthCheckScheduler::class,
    EntitlementSyncService::class,
    EntitlementRefreshScheduler::class,
    EntitlementRevisionTrigger::class,
    SupportEntitlementService::class,
    RefreshEntitlementsHandler::class,
    SupportNavContributor::class,
    SupportOverviewHandler::class,
    SupportOverviewRoutes::class,
)
class SupportModuleAutoConfiguration
