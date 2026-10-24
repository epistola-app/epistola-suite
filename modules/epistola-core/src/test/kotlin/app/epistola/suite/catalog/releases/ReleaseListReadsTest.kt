// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.releases

import app.epistola.suite.assets.AssetMediaType
import app.epistola.suite.assets.commands.UploadAsset
import app.epistola.suite.catalog.CatalogKey
import app.epistola.suite.catalog.commands.CreateCatalog
import app.epistola.suite.common.ids.AssetKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.releaseNext
import app.epistola.suite.testing.testCatalog
import app.epistola.suite.themes.commands.CreateTheme
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.jdbi.v3.core.statement.SqlLogger
import org.jdbi.v3.core.statement.SqlStatements
import org.jdbi.v3.core.statement.StatementContext
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.sql.SQLException
import javax.imageio.ImageIO

/**
 * A release read loads only what it shows (ADR 0033): its resource list reads `release_resources`
 * alone, a type's resources read that type's contents, and neither reads a binary or the manifest.
 */
class ReleaseListReadsTest : IntegrationTestBase() {
    @Autowired private lateinit var jdbi: Jdbi

    private val letters = CatalogKey.of("letters")

    @Test
    fun `listing a release reads its rows, a type's resources that type's contents, and no binary`() {
        val tenant = withMediator { createTenant("lean-release-reads").id }
        withMediator {
            CreateCatalog(tenant, letters, "Letters").execute()
            CreateTheme(ThemeId(ThemeKey.of("brand"), testCatalog(tenant, letters)), "Brand").execute()
            UploadAsset(testCatalog(tenant, letters), "Logo", AssetMediaType.PNG, png(), 1, 1, id = AssetKey.of("logo")).execute()
            mediator.releaseNext(testCatalog(TenantId(tenant), letters))
        }

        val listed = reads { withMediator { ListReleaseResources(testCatalog(tenant, letters), "1.0.0").query() } }
        assertThat(listed.result.map { "${it.type}/${it.key} ${it.name}" }).contains("theme/brand Brand", "image/logo Logo")
        assertThat(listed.statements).anyMatch { "FROM release_resources" in it }
            .noneMatch { "resource_contents" in it || "asset_content" in it || "manifest_snapshot" in it }

        val themes = reads { withMediator { ListReleasedResources(testCatalog(tenant, letters), "1.0.0", "theme").query() } }
        assertThat(themes.result.map { it.key }).containsExactly("brand")
        assertThat(themes.statements).noneMatch { "asset_content" in it || "manifest_snapshot" in it }
    }

    private data class Reads<T>(val result: T, val statements: List<String>)

    /** Runs [block] and collects the SQL this thread executed. */
    private fun <T> reads(block: () -> T): Reads<T> {
        val config = jdbi.getConfig(SqlStatements::class.java)
        val original = config.sqlLogger
        val thread = Thread.currentThread()
        val statements = mutableListOf<String>()
        config.sqlLogger = object : SqlLogger {
            override fun logBeforeExecution(context: StatementContext) {
                if (Thread.currentThread() == thread) statements += context.renderedSql
                original.logBeforeExecution(context)
            }

            override fun logAfterExecution(context: StatementContext) = original.logAfterExecution(context)

            override fun logException(context: StatementContext, ex: SQLException) = original.logException(context, ex)
        }
        return try {
            Reads(block(), statements.toList())
        } finally {
            config.sqlLogger = original
        }
    }

    private fun png(): ByteArray = ByteArrayOutputStream().use { out ->
        ImageIO.write(BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", out)
        out.toByteArray()
    }
}
