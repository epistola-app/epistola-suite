// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.loadtest.model

/**
 * Status of a load test run.
 */
enum class LoadTestStatus {
    /** Load test has been created but not yet started */
    PENDING,

    /** Load test is currently executing */
    RUNNING,

    /** Load test completed successfully */
    COMPLETED,

    /** Load test failed due to an error */
    FAILED,

    /** Load test was cancelled by user */
    CANCELLED,
}
