// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.handlers

import app.epistola.suite.handlers.CatalogHandler.DeploymentRowView
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** An environment's row on the catalog pages: when it carries the Production mark. */
class DeploymentRowViewTest {

    private fun row(name: String, production: Boolean) = DeploymentRowView(environmentKey = "env", environmentName = name, production = production, served = emptyList())

    @Test
    fun `a production environment is marked, unless its name already says Production`() {
        assertThat(row("Live", production = true).showsProductionMark).isTrue()
        assertThat(row("Production", production = true).showsProductionMark).`as`("not \"Production Production\"").isFalse()
        assertThat(row("production", production = true).showsProductionMark).`as`("whatever its case").isFalse()
        assertThat(row("Production EU", production = true).showsProductionMark).`as`("only a name that is just Production").isTrue()
        assertThat(row("Test", production = false).showsProductionMark).isFalse()
    }
}
