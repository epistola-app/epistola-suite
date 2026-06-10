package app.epistola.suite.support.upgrading

import app.epistola.suite.support.upgrading.ui.UpgradingHandler
import app.epistola.suite.support.upgrading.ui.UpgradingNavContributor
import app.epistola.suite.support.upgrading.ui.UpgradingRoutes
import app.epistola.suite.upgrading.CompatibilitySyncFallbackConfiguration
import app.epistola.suite.upgrading.UpgradingSnapshotProperties
import app.epistola.suite.upgrading.UpgradingSnapshotScheduler
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Import

@AutoConfiguration
@ConditionalOnSupportUpgradingModule
@EnableConfigurationProperties(UpgradingSnapshotProperties::class)
@Import(
    SupportUpgradingConfiguration::class,
    CompatibilitySyncFallbackConfiguration::class,
    UpgradingSnapshotScheduler::class,
    UpgradingHandler::class,
    UpgradingRoutes::class,
    UpgradingNavContributor::class,
)
class SupportUpgradingModuleAutoConfiguration
