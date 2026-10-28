// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.catalog.migrations

import app.epistola.catalog.migration.CatalogWireSchema
import app.epistola.catalog.protocol.StencilResource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A stencil from an archive before wire v8 keeps the scope 1.x gave it: its content and every fill
 * saw the template's data and the instance's parameters. A v8 stencil says what it sees itself.
 */
class OneXStencilScopeTest {
    private val migrator = CatalogSchemaMigrator()

    @Test
    fun `a stencil from before wire v8 reads the template's data and passes its parameters to every placeholder`() {
        val manifest = migrator.migrateAndBindManifest(manifest(7), namespace = "acme")

        val stencil = migrator.migrateAndBindResourceDetail("stencil", stencil(7), manifest.catalog).resource as StencilResource

        assertThat(stencil.readsTemplateData).isTrue()
        assertThat(placeholders(stencil).map { it["passParameters"] }).containsExactly(true, true)
    }

    @Test
    fun `a wire-v8 stencil keeps the scope it declares`() {
        val manifest = migrator.migrateAndBindManifest(manifest(CatalogWireSchema.CURRENT_VERSION), namespace = null)

        val stencil = migrator.migrateAndBindResourceDetail("stencil", stencil(CatalogWireSchema.CURRENT_VERSION), manifest.catalog).resource as StencilResource

        assertThat(stencil.readsTemplateData).isFalse()
        assertThat(placeholders(stencil).map { it["passParameters"] }).containsExactly(null, null)
    }

    private fun placeholders(stencil: StencilResource) = stencil.content.nodes.values.filter { it.type == "placeholder" }.sortedBy { it.id }.map { it.props.orEmpty() }

    private fun manifest(version: Int): ByteArray =
        """
        {
          "schemaVersion": $version,
          "catalog": ${if (version >= 8) "{\"key\": \"letters\", \"name\": \"Letters\", \"registry\": \"exchange.epistola.app\", \"namespace\": \"acme\"}" else "{\"slug\": \"letters\", \"name\": \"Letters\"}"},
          "publisher": {"name": "Test"},
          "release": {"version": "1.0.0"},
          "resources": [{"type": "stencil", ${if (version >= 8) "\"key\"" else "\"slug\""}: "letterhead", "name": "Letterhead", "detailUrl": "./resources/stencil/letterhead.json"}]
        }
        """.trimIndent().toByteArray()

    /** A letterhead that prints the recipient from the template's data and has two placeholders. */
    private fun stencil(version: Int): ByteArray =
        """
        {
          "schemaVersion": $version,
          "resource": {
            "type": "stencil", ${if (version >= 8) "\"key\"" else "\"slug\""}: "letterhead", "name": "Letterhead",
            "content": {
              "modelVersion": 1, "root": "root",
              "nodes": {
                "root": {"id": "root", "type": "root", "slots": ["root-children"]},
                "recipient": {"id": "recipient", "type": "text", "slots": [], "props": {"content": {"type": "doc", "content": [{"type": "paragraph", "content": [{"type": "expression", "attrs": {"expression": "recipient.name"}}]}]}}},
                "p-greeting": {"id": "p-greeting", "type": "placeholder", "slots": ["p-greeting-default", "p-greeting-fill"], "props": {"name": "greeting", "kind": "block"}},
                "p-body": {"id": "p-body", "type": "placeholder", "slots": ["p-body-default", "p-body-fill"], "props": {"name": "body", "kind": "block"}}
              },
              "slots": {
                "root-children": {"id": "root-children", "nodeId": "root", "name": "children", "children": ["recipient", "p-greeting", "p-body"]},
                "p-greeting-default": {"id": "p-greeting-default", "nodeId": "p-greeting", "name": "default", "children": []},
                "p-greeting-fill": {"id": "p-greeting-fill", "nodeId": "p-greeting", "name": "fill", "children": []},
                "p-body-default": {"id": "p-body-default", "nodeId": "p-body", "name": "default", "children": []},
                "p-body-fill": {"id": "p-body-fill", "nodeId": "p-body", "name": "fill", "children": []}
              }
            }
          }
        }
        """.trimIndent().toByteArray()
}
