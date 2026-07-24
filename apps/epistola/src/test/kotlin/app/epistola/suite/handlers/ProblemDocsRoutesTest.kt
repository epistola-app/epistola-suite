// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.BaseIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.http.HttpStatus

class ProblemDocsRoutesTest : BaseIntegrationTest() {

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Test
    fun `problem type page renders human-readable documentation`() {
        val response = restTemplate.getForEntity("/errors/catalog-read-only", String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body).contains("Catalog Read Only")
        assertThat(response.body).contains("https://epistola.app/errors/catalog-read-only")
        assertThat(response.body).contains("CATALOG_READ_ONLY")
    }

    @Test
    fun `unknown problem type returns 404`() {
        val response = restTemplate.getForEntity("/errors/does-not-exist", String::class.java)

        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }
}
