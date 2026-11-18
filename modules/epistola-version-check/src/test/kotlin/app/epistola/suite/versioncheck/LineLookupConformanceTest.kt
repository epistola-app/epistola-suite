// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.versioncheck

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import tools.jackson.databind.JsonNode
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.treeToValue

/**
 * The published test cases for clients of releases.json, run against [LineLookup].
 *
 * `releases/releases.conformance.json` is a copy of https://epistola.app/releases.conformance.json
 * (website, src/lib/server/releases/). The website only adds cases; copy the new file over when it
 * does.
 */
class LineLookupConformanceTest {
    private val mapper = jacksonObjectMapper()

    @TestFactory
    fun `passes every published case`(): List<DynamicTest> {
        val conformance = javaClass.getResourceAsStream("/releases/releases.conformance.json")
            .let { requireNotNull(it) { "releases.conformance.json on the test classpath" } }
            .use { mapper.readTree(it) }
        val products = conformance["products"].properties().associate { (key, value) ->
            key to mapper.treeToValue<ProductReleases>(value)
        }
        val cases = conformance["cases"]
        return (0 until cases.size()).map { cases[it] }.map { case ->
            DynamicTest.dynamicTest(case["name"].asString()) {
                val result = LineLookup.evaluate(products.getValue(case["product"].asString()), case["version"].asString())
                val expect = case["expect"]
                assertThat(
                    mapOf(
                        "status" to result.status.name.lowercase(),
                        "line" to result.line,
                        "patch" to result.patch?.version,
                        "newerRelease" to result.newerRelease?.version,
                        "nextPrerelease" to result.nextPrerelease?.version,
                    ),
                ).isEqualTo(
                    listOf("status", "line", "patch", "newerRelease", "nextPrerelease").associateWith { text(expect[it]) },
                )
            }
        }.also { check(it.isNotEmpty()) { "no conformance cases" } }
    }

    private fun text(node: JsonNode?): String? = if (node == null || node.isNull) null else node.asString()
}
