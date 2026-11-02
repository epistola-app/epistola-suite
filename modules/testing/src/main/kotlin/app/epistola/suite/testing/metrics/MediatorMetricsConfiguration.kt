// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.testing.metrics

import app.epistola.suite.mediator.Mediator
import app.epistola.suite.mediator.SpringMediator
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary

/**
 * Registers the [MetricsRecordingMediator] as the primary [Mediator] in every
 * test context, so all command/query dispatches are profiled for the test-run
 * metrics harness. Imported by `IntegrationTestBase` (and thus inherited by every
 * integration and app test). Delegates to the real [SpringMediator] (injected by
 * concrete type to avoid a self-referential `@Primary` cycle).
 */
@TestConfiguration(proxyBeanMethods = false)
class MediatorMetricsConfiguration {
    @Bean
    @Primary
    fun metricsRecordingMediator(delegate: SpringMediator): Mediator = MetricsRecordingMediator(delegate)
}
