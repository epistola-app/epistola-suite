// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.stencils

import app.epistola.catalog.validation.TemplateValidationLimits
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.StencilKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.common.ids.VersionId
import app.epistola.suite.mediator.execute
import app.epistola.suite.stencils.commands.CreateStencil
import app.epistola.suite.stencils.commands.UpdateStencil
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.versions.PublishVersion
import app.epistola.suite.templates.commands.versions.UpdateDraft
import app.epistola.suite.templates.validation.hasValidationCode
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestIdHelpers
import app.epistola.suite.testing.withRequiredDataExample
import app.epistola.suite.validation.ValidationCode
import app.epistola.suite.validation.ValidationException
import app.epistola.template.model.Node
import app.epistola.template.model.Slot
import app.epistola.template.model.TemplateDocument
import app.epistola.template.model.ThemeRef
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Placeholders and nesting with stencils used by reference (contract ADR 0006), through the mediator
 * with a real Postgres: what the structural validators refuse on write, and what only the stencils
 * as they are can tell, which publishing refuses.
 */
class StencilPlaceholderIntegrationTest : IntegrationTestBase() {

    /** Forces a Unit return so JUnit discovers the method. */
    private fun test(block: () -> Unit) = withMediator(block)

    private fun stencilId(tenantId: TenantId, key: String? = null) = StencilId(key?.let(StencilKey::of) ?: TestIdHelpers.nextStencilId(), CatalogId.default(tenantId))

    private fun template(tenantId: TenantId, name: String): VariantId {
        val templateId = TemplateId(TestIdHelpers.nextTemplateId(), CatalogId.default(tenantId))
        CreateDocumentTemplate(id = templateId, name = name).execute().withRequiredDataExample()
        return VariantId(VariantKey.INITIAL, templateId)
    }

    private fun publish(variantId: VariantId, model: TemplateDocument) = PublishVersion(VersionId(UpdateDraft(variantId, model).execute()!!.id, variantId)).execute()

    private fun using(key: String) = stencilDocument {
        stencil("inst", key)
        build("inst")
    }

    // ── Refused on write ──

    @Test
    fun `UpdateDraft rejects a placeholder without stencil ancestor`() = test {
        val variantId = template(TenantId(createTenant("placeholder-bare").id), "Bare placeholder")

        val invalid = stencilDocument {
            placeholder("ph", "body")
            build("ph")
        }

        assertThatThrownBy { UpdateDraft(variantId = variantId, templateModel = invalid).execute() }
            .isInstanceOf(ValidationException::class.java)
            .hasValidationCode(ValidationCode.PLACEHOLDER_OUTSIDE_STENCIL)
    }

    @Test
    fun `CreateStencil rejects content with duplicate placeholder names`() = test {
        val duplicate = stencilDocument {
            placeholder("ph1", "body")
            placeholder("ph2", "body")
            build("ph1", "ph2")
        }

        assertThatThrownBy { CreateStencil(id = stencilId(TenantId(createTenant("placeholder-dup").id)), name = "Dup", content = duplicate).execute() }
            .isInstanceOf(ValidationException::class.java)
            .hasValidationCode(ValidationCode.PLACEHOLDER_NAME_DUPLICATE)
    }

    @Test
    fun `CreateStencil rejects initial content without synthetic root node`() = test {
        val invalid = TemplateDocument(
            modelVersion = 1,
            root = "text-root",
            nodes = mapOf("text-root" to Node(id = "text-root", type = "text", slots = emptyList())),
            slots = emptyMap(),
            themeRef = ThemeRef.Inherit,
        )

        assertThatThrownBy { CreateStencil(id = stencilId(TenantId(createTenant("stencil-graph-root").id)), name = "Invalid root", content = invalid).execute() }
            .isInstanceOf(ValidationException::class.java)
            .hasValidationCode(ValidationCode.TEMPLATE_GRAPH_INVALID)
    }

    @Test
    fun `UpdateDraft rejects malformed parameterBindings prop`() = test {
        val variantId = template(TenantId(createTenant("placeholder-binding-shape").id), "Malformed binding")
        val malformed = TemplateDocument(
            modelVersion = 1,
            root = "root",
            nodes = mapOf(
                "root" to Node(id = "root", type = "root", slots = listOf("root-slot")),
                "nested" to Node(id = "nested", type = "stencil", slots = emptyList(), props = mapOf("stencilKey" to "other", "parameterBindings" to "not-a-map")),
            ),
            slots = mapOf("root-slot" to Slot(id = "root-slot", nodeId = "root", name = "children", children = listOf("nested"))),
            themeRef = ThemeRef.Inherit,
        )

        assertThatThrownBy { UpdateDraft(variantId = variantId, templateModel = malformed).execute() }
            .isInstanceOf(ValidationException::class.java)
            .hasValidationCode(ValidationCode.NODE_PARAMETER_BINDINGS_INVALID_SHAPE)
    }

    // ── Refused on publish: only the stencils as they are can tell ──

    @Test
    fun `saving a stencil whose own content uses it is refused, also inside a fill`() = test {
        val tenantId = TenantId(createTenant("placeholder-self").id)
        val box = stencilId(tenantId, "box")
        CreateStencil(id = box, name = "Box").execute()
        // Inside the stencil's own content, a fill is still the stencil's: box(fill: box) is box in box.
        val selfInFill = stencilDocument {
            stencil("inner", "box")
            placeholder("fill", "inside", fill = listOf("inner"))
            stencil("frame", "frame", listOf("fill"))
            build("frame")
        }

        assertThatThrownBy { UpdateStencil(id = box, content = using("box")).execute() }
            .isInstanceOf(ValidationException::class.java)
            .hasValidationCode(ValidationCode.STENCIL_RECURSION)
        assertThatThrownBy { UpdateStencil(id = box, content = selfInFill).execute() }
            .isInstanceOf(ValidationException::class.java)
            .hasValidationCode(ValidationCode.STENCIL_RECURSION)
        assertThatThrownBy { CreateStencil(id = stencilId(tenantId, "loop"), name = "Loop", content = using("loop")).execute() }
            .isInstanceOf(ValidationException::class.java)
            .hasValidationCode(ValidationCode.STENCIL_RECURSION)
    }

    @Test
    fun `publishing refuses a stencil that contains itself through another`() = test {
        val tenantId = TenantId(createTenant("placeholder-recursion").id)
        CreateStencil(id = stencilId(tenantId, "header"), name = "Header").execute()
        CreateStencil(id = stencilId(tenantId, "address"), name = "Address", content = using("header")).execute()
        // header → address → header: only the second write closes the cycle.
        UpdateStencil(id = stencilId(tenantId, "header"), content = using("address")).execute()

        assertThatThrownBy { publish(template(tenantId, "Recursive"), using("header")) }
            .isInstanceOf(ValidationException::class.java)
            .hasValidationCode(ValidationCode.STENCIL_RECURSION)
    }

    @Test
    fun `publishing refuses stencils nested deeper than the limit`() = test {
        val tenantId = TenantId(createTenant("placeholder-depth").id)
        val depth = TemplateValidationLimits.MAX_STENCIL_NESTING_DEPTH + 1
        // stencil-0 uses stencil-1 uses … stencil-(depth-1), which is plain.
        (depth - 1 downTo 0).forEach { level ->
            val content = if (level == depth - 1) stencilDocument { build() } else using("stencil-${level + 1}")
            CreateStencil(id = stencilId(tenantId, "stencil-$level"), name = "Level $level", content = content).execute()
        }

        assertThatThrownBy { publish(template(tenantId, "Too deeply nested"), using("stencil-0")) }
            .isInstanceOf(ValidationException::class.java)
            .hasValidationCode(ValidationCode.STENCIL_NESTING_DEPTH_EXCEEDED)
    }

    @Test
    fun `a fill may use the same stencil it fills, which is not recursion`() = test {
        val tenantId = TenantId(createTenant("placeholder-fill-reuse").id)
        CreateStencil(
            id = stencilId(tenantId, "box"),
            name = "Box",
            content = stencilDocument {
                placeholder("inside", "inside")
                build("inside")
            },
        ).execute()

        val boxInBox = stencilDocument {
            stencil("inner", "box")
            placeholder("fill", "inside", fill = listOf("inner"))
            stencil("outer", "box", listOf("fill"))
            build("outer")
        }

        assertThat(publish(template(tenantId, "Box in a box"), boxInBox)).isNotNull
    }

    @Test
    fun `a published template stores nested instances by reference`() = test {
        val tenantId = TenantId(createTenant("placeholder-nesting").id)
        CreateStencil(
            id = stencilId(tenantId, "letter"),
            name = "Letter",
            content = stencilDocument {
                text("letter-text", "Letter content")
                placeholder("body", "body")
                build("letter-text", "body")
            },
        ).execute()
        CreateStencil(
            id = stencilId(tenantId, "address"),
            name = "Address",
            content = stencilDocument {
                text("address-text", "Nested address content")
                build("address-text")
            },
        ).execute()

        val nested = stencilDocument {
            stencil("inner", "address")
            placeholder("fill", "body", fill = listOf("inner"))
            stencil("outer", "letter", listOf("fill"))
            build("outer")
        }
        val published = publish(template(tenantId, "Nested stencils"), nested)!!

        assertThat(published.templateModel.nodes.values.filter { it.type == "stencil" }.map { it.props!!["stencilKey"] })
            .containsExactlyInAnyOrder("letter", "address")
        assertThat(published.templateModel.texts()).isEmpty()
    }
}
