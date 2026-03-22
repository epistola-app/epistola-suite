package app.epistola.suite.loadtest.commands

import app.epistola.suite.common.ids.TenantKey
import app.epistola.suite.loadtest.model.LoadTestRunKey
import app.epistola.suite.loadtest.model.LoadTestStatus
import app.epistola.suite.mediator.Command
import app.epistola.suite.mediator.CommandHandler
import app.epistola.suite.security.Permission
import app.epistola.suite.security.RequiresPermission
import org.jdbi.v3.core.Jdbi
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Command to cancel a running load test.
 *
 * @property tenantId Tenant that owns the load test
 * @property runId Load test run to cancel
 */
data class CancelLoadTest(
    val tenantId: TenantKey,
    val runId: LoadTestRunKey,
) : Command<Unit>,
    RequiresPermission {
    override val permission get() = Permission.DOCUMENT_GENERATE
    override val tenantKey get() = tenantId
}

@Component
class CancelLoadTestHandler(
    private val jdbi: Jdbi,
) : CommandHandler<CancelLoadTest, Unit> {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun handle(command: CancelLoadTest) {
        logger.info("Cancelling load test run {} for tenant {}", command.runId, command.tenantId)

        val updated = jdbi.withHandle<Int, Exception> { handle ->
            handle.createUpdate(
                """
                UPDATE load_test_runs
                SET status = :cancelledStatus,
                    completed_at = NOW()
                WHERE id = :runId
                  AND tenant_key = :tenantId
                  AND status IN (:runningStatus, :pendingStatus)
                """,
            )
                .bind("runId", command.runId)
                .bind("tenantId", command.tenantId)
                .bind("cancelledStatus", LoadTestStatus.CANCELLED.name)
                .bind("runningStatus", LoadTestStatus.RUNNING.name)
                .bind("pendingStatus", LoadTestStatus.PENDING.name)
                .execute()
        }

        if (updated == 0) {
            logger.warn("Load test run {} not found or already completed", command.runId)
            throw IllegalStateException("Load test run ${command.runId} cannot be cancelled (not found or already completed)")
        }

        logger.info("Successfully cancelled load test run {}", command.runId)
    }
}
