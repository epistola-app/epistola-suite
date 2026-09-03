// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.demo

import app.epistola.suite.EpistolaSuiteApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Import

/**
 * The demo distribution: the whole suite, plus demo mode.
 *
 * Published as `epistola-suite:{version}-demo`. `apps/epistola` publishes
 * `epistola-suite:{version}` and does not contain this package at all — that separation is the
 * point. Demo mode gives every person who logs in a tenant of their own, and can carry a shared
 * secret that authenticates every `/api` endpoint against every tenant with every permission. A
 * profile flag would be a weak boundary for that: anyone who can edit a deployment's environment
 * would be one variable away. A separate artifact means no amount of configuration can turn a
 * production install into a demo, because the classes are not there to configure.
 *
 * [EpistolaSuiteApplication] is imported rather than reimplemented, so its component scan,
 * auto-configuration exclusions and property scan apply unchanged and the two apps cannot drift.
 * The scans below cover this package on top of it.
 *
 * Everything demo-specific — the loader, the tenant resolver, the shared-secret filter, the landing,
 * and the bundled demo catalog under `resources/epistola/catalogs/demo/` — lives in this app.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@Import(EpistolaSuiteApplication::class)
class EpistolaDemoApplication

fun main(args: Array<String>) {
    runApplication<EpistolaDemoApplication>(*args)
}
