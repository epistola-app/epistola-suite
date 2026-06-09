package app.epistola.suite.support.feedback

import app.epistola.suite.feedback.commands.AddFeedbackAssetHandler
import app.epistola.suite.feedback.commands.AddFeedbackCommentHandler
import app.epistola.suite.feedback.commands.CreateFeedbackHandler
import app.epistola.suite.feedback.commands.SyncFeedbackCommentHandler
import app.epistola.suite.feedback.commands.SyncFeedbackStatusHandler
import app.epistola.suite.feedback.commands.UpdateFeedbackCommentExternalRefHandler
import app.epistola.suite.feedback.commands.UpdateFeedbackStatusHandler
import app.epistola.suite.feedback.commands.UpdateFeedbackSyncRefHandler
import app.epistola.suite.feedback.commands.UpdateFeedbackSyncStatusHandler
import app.epistola.suite.feedback.queries.GetFeedbackAssetContentHandler
import app.epistola.suite.feedback.queries.GetFeedbackByExternalRefHandler
import app.epistola.suite.feedback.queries.GetFeedbackCommentsHandler
import app.epistola.suite.feedback.queries.GetFeedbackHandler
import app.epistola.suite.feedback.queries.ListFeedbackAssetsHandler
import app.epistola.suite.feedback.queries.ListFeedbackHandler
import app.epistola.suite.feedback.queries.ListPendingSyncFeedbackHandler
import app.epistola.suite.feedback.queries.ListUnsyncedCommentsHandler
import app.epistola.suite.feedback.sync.FeedbackPollScheduler
import app.epistola.suite.feedback.sync.FeedbackSyncFallbackConfiguration
import app.epistola.suite.feedback.sync.FeedbackSyncProperties
import app.epistola.suite.feedback.sync.FeedbackSyncScheduler
import app.epistola.suite.feedback.sync.OnFeedbackCommentAdded
import app.epistola.suite.feedback.sync.OnFeedbackCreated
import app.epistola.suite.feedback.sync.OnFeedbackStatusChanged
import app.epistola.suite.support.feedback.ui.FeedbackFooterContributor
import app.epistola.suite.support.feedback.ui.FeedbackHandler
import app.epistola.suite.support.feedback.ui.FeedbackNavContributor
import app.epistola.suite.support.feedback.ui.FeedbackRoutes
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Import

@AutoConfiguration
@ConditionalOnSupportFeedbackModule
@EnableConfigurationProperties(FeedbackSyncProperties::class)
@Import(
    SupportFeedbackConfiguration::class,
    FeedbackSyncFallbackConfiguration::class,
    CreateFeedbackHandler::class,
    AddFeedbackAssetHandler::class,
    AddFeedbackCommentHandler::class,
    UpdateFeedbackStatusHandler::class,
    UpdateFeedbackSyncRefHandler::class,
    UpdateFeedbackSyncStatusHandler::class,
    UpdateFeedbackCommentExternalRefHandler::class,
    SyncFeedbackCommentHandler::class,
    SyncFeedbackStatusHandler::class,
    GetFeedbackHandler::class,
    GetFeedbackAssetContentHandler::class,
    GetFeedbackByExternalRefHandler::class,
    GetFeedbackCommentsHandler::class,
    ListFeedbackHandler::class,
    ListFeedbackAssetsHandler::class,
    ListPendingSyncFeedbackHandler::class,
    ListUnsyncedCommentsHandler::class,
    FeedbackSyncScheduler::class,
    FeedbackPollScheduler::class,
    OnFeedbackCreated::class,
    OnFeedbackCommentAdded::class,
    OnFeedbackStatusChanged::class,
    FeedbackHandler::class,
    FeedbackRoutes::class,
    FeedbackNavContributor::class,
    FeedbackFooterContributor::class,
)
class SupportFeedbackModuleAutoConfiguration
