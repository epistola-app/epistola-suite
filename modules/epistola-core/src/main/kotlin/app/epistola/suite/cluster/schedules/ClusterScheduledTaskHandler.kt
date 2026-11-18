// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.cluster.schedules

/**
 * Handler contract for recurring cluster scheduled tasks.
 *
 * Implementations are Spring beans discovered by
 * [ClusterScheduledTaskScheduler]. [taskType] is an explicit dispatch key that
 * must match exactly one registered [ClusterScheduledTaskDefinition]. Handlers
 * should be idempotent because a failed or expired lease may cause the same due
 * occurrence to be retried.
 *
 * A task with a tenant runs as that tenant's system principal
 * (`SystemUser.principalForTenant`). A system-wide task runs with no principal,
 * so it dispatches only `SystemInternal` operations, or binds a tenant's system
 * principal itself before it touches that tenant (as the support-request sync
 * does). A role-less principal is deliberately not bound: `RequiresAuthentication`
 * reads such as `ListTenants` would then pass and return nothing instead of
 * failing (#551).
 */
interface ClusterScheduledTaskHandler {
    val taskType: String

    fun handle(task: ClusterScheduledTask)
}
