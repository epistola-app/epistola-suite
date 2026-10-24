// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.revisions

import app.epistola.suite.attributes.codelists.commands.CreateCodeList
import app.epistola.suite.attributes.codelists.model.CodeListEntry
import app.epistola.suite.attributes.codelists.model.CodeListSource
import app.epistola.suite.catalog.AuthType
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.CodeListId
import app.epistola.suite.common.ids.CodeListKey
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.ThemeId
import app.epistola.suite.common.ids.ThemeKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.default
import app.epistola.suite.testing.releaseNext
import app.epistola.suite.themes.commands.CreateTheme
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import tools.jackson.databind.ObjectMapper

/**
 * Every stored content of a type stored as JSON has one shape (ADR 0033):
 * `{ "resource": <released form>, ... }`, whoever wrote it (a draft save, a release cut here, a
 * release received, the bundled catalog installed), and [StoredContent.resourceOf] refuses any other.
 */
class StoredContentShapeTest : IntegrationTestBase() {

    @Autowired
    private lateinit var jdbi: Jdbi

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Test
    fun `every stored content of a converted type has a resource object`() {
        val tenant = TenantId(createTenant("Stored content shape").id)
        val catalog = CatalogId.default(tenant)
        withMediator {
            CreateTheme(ThemeId(ThemeKey.of("brand"), catalog), "Brand").execute()
            CreateCodeList(CodeListId(CodeListKey.of("inline"), catalog), "Inline", sourceType = CodeListSource.INLINE, entries = listOf(CodeListEntry("a", "Alpha"))).execute()
            CreateCodeList(CodeListId(CodeListKey.of("remote"), catalog), "Remote", sourceType = CodeListSource.URL, sourceUrl = "https://example.com/list.json", authType = AuthType.BEARER, credential = "s3cret").execute()
            mediator.releaseNext(catalog)
        }

        val counts = jdbi.withHandle<Pair<Int, Int>, Exception> { h ->
            h.createQuery(
                """
                SELECT count(*) FILTER (WHERE jsonb_typeof(payload -> 'resource') = 'object') AS enveloped,
                       count(*) FILTER (WHERE jsonb_typeof(payload -> 'resource') IS DISTINCT FROM 'object') AS bare
                FROM resource_contents WHERE tenant_key = :t AND kind IN (<kinds>)
                """,
            ).bind("t", tenant.key).bindList("kinds", StoredContent.ENVELOPED_TYPES.toList())
                .map { rs, _ -> rs.getInt("enveloped") to rs.getInt("bare") }.one()
        }
        // The tenant's system catalog (installed, a received release) and this catalog (drafts and a release cut here).
        assertThat(counts.first).`as`("contents in the envelope").isGreaterThan(3)
        assertThat(counts.second).`as`("contents of a converted type without a resource object").isZero()
    }

    @Test
    fun `resourceOf refuses a content that is not in the envelope`() {
        val bare = objectMapper.readTree("""{"key": "brand", "name": "Brand", "type": "theme"}""")
        assertThatThrownBy { StoredContent.resourceOf(bare) }.isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy { StoredContent.resourceOf(objectMapper.readTree("""{"resource": "brand"}""")) }.isInstanceOf(IllegalStateException::class.java)
        assertThat(StoredContent.resourceOf(objectMapper.readTree("""{"resource": {"key": "brand"}}""")).path("key").asString()).isEqualTo("brand")
    }
}
