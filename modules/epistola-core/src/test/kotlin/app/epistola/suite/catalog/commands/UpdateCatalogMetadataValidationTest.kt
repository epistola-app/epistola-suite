// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.commands

import app.epistola.catalog.protocol.CatalogLicense
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.common.ids.TenantKey
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class UpdateCatalogMetadataValidationTest {

    @Test
    fun `accepts SPDX expression syntax`() {
        listOf(
            "CC-BY-4.0",
            "MIT OR Apache-2.0",
            "GPL-2.0-only WITH Classpath-exception-2.0",
            "(MIT OR Apache-2.0) AND LicenseRef-Proprietary",
            "DocumentRef-vendor:LicenseRef-Proprietary",
        ).forEach { expression ->
            assertThatCode { commandWithSpdx(expression) }
                .describedAs(expression)
                .doesNotThrowAnyException()
        }
    }

    @Test
    fun `rejects malformed SPDX expression syntax`() {
        listOf(
            "MIT OR",
            "MIT Apache-2.0",
            "(MIT OR Apache-2.0",
            "MIT WITH",
            "MIT WITH LicenseRef-CustomException",
            "(MIT OR Apache-2.0) WITH Classpath-exception-2.0",
            "MIT + Apache-2.0",
        ).forEach { expression ->
            assertThatThrownBy { commandWithSpdx(expression) }
                .describedAs(expression)
                .hasMessage("SPDX expression has invalid syntax.")
        }
    }

    private fun commandWithSpdx(expression: String) = UpdateCatalogMetadata(
        tenantKey = TenantKey.of("tenant"),
        catalogKey = CatalogKey.of("catalog"),
        name = "Catalog",
        description = null,
        license = CatalogLicense(name = "License", spdxExpression = expression),
    )
}
