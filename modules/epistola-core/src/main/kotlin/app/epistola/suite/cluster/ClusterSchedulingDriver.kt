// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.cluster

/**
 * Strategy marker for the substrate that decides *when* the cluster scheduling
 * engines run.
 *
 * The engines — [ClusterNodeHeartbeatScheduler],
 * [app.epistola.suite.cluster.timers.ClusterTimerScheduler], and
 * [app.epistola.suite.cluster.schedules.ClusterScheduledTaskScheduler] — own
 * *what* happens on a tick (due-selection, ownership, leases, dispatch) and are
 * plain directly-invokable components. A driver owns *when* they are invoked:
 *
 * - Production uses [WallClockClusterSchedulingDriver], which ticks the engines
 *   on fixed wall-clock delays (`epistola.cluster.*-interval-ms` properties).
 * - Integration tests use the deterministic driver from `modules/testing`,
 *   which runs due work synchronously on the test thread under the scoped test
 *   clock. A wall-clock tick can never participate in test time: the test clock
 *   is bound via `ScopedValue` on the test thread, so a background scheduler
 *   thread always evaluates `next_due_at <= now` against the system clock.
 *
 * Substrate selection is property-gated (`epistola.cluster.scheduling-substrate`)
 * so the two drivers are mutually exclusive regardless of configuration-class
 * ordering — see [ClusterSchedulingConfiguration].
 */
interface ClusterSchedulingDriver
