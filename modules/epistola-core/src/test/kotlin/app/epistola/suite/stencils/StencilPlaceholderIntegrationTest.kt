package app.epistola.suite.stencils

import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.StencilId
import app.epistola.suite.common.ids.StencilVersionId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.common.ids.VariantKey
import app.epistola.suite.common.ids.VersionKey
import app.epistola.suite.mediator.execute
import app.epistola.suite.stencils.commands.CreateStencil
import app.epistola.suite.stencils.commands.PublishStencilVersion
import app.epistola.suite.stencils.commands.UpdateStencilInTemplate
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.versions.UpdateDraft
import app.epistola.suite.templates.validation.hasValidationCode
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestIdHelpers
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
 * End-to-end integration test for the stencil placeholders feature.
 *
 * Exercises the full path through the mediator with a real Postgres:
 * `PlaceholderValidator` wiring on the four commands, `StencilContentReplacer`
 * fill preservation, and `UpdateStencilInTemplate` returning `droppedFills`.
 *
 * Wiring this layer matters because the per-class unit tests cannot detect
 * regressions where a validator is not actually invoked or a command's return
 * type is not threaded through to the JSONB store correctly.
 */
class StencilPlaceholderIntegrationTest : IntegrationTestBase() {

    /**
     * Force Unit return so JUnit 5 actually discovers the method. `withMediator`
     * is generic — without this wrapper, a lambda whose last expression is an
     * AssertJ assertion would make the test method's return type non-void and
     * Jupiter would silently skip it.
     */
    private fun test(block: () -> Unit) = withMediator(block)

    private fun stencilId(tenantId: TenantId) = StencilId(TestIdHelpers.nextStencilId(), CatalogId.default(tenantId))

    /** Stencil v1: declares a placeholder named "body" with a default text. */
    private fun stencilV1WithBodyPlaceholder(): TemplateDocument = TemplateDocument(
        modelVersion = 1,
        root = "root",
        nodes = mapOf(
            "root" to Node(id = "root", type = "root", slots = listOf("root-slot")),
            "ph-body" to Node(
                id = "ph-body",
                type = "placeholder",
                slots = listOf("ph-body-fill"),
                props = mapOf("name" to "body", "kind" to "block"),
            ),
            "default-text" to Node(
                id = "default-text",
                type = "text",
                slots = emptyList(),
                props = mapOf("content" to "Default body content"),
            ),
        ),
        slots = mapOf(
            "root-slot" to Slot(id = "root-slot", nodeId = "root", name = "children", children = listOf("ph-body")),
            "ph-body-fill" to Slot(id = "ph-body-fill", nodeId = "ph-body", name = "fill", children = listOf("default-text")),
        ),
        themeRef = ThemeRef.Inherit,
    )

    /** Stencil v2: placeholder renamed from "body" to "main". */
    private fun stencilV2RenamedPlaceholder(): TemplateDocument = TemplateDocument(
        modelVersion = 1,
        root = "root",
        nodes = mapOf(
            "root" to Node(id = "root", type = "root", slots = listOf("root-slot")),
            "ph-main" to Node(
                id = "ph-main",
                type = "placeholder",
                slots = listOf("ph-main-fill"),
                props = mapOf("name" to "main", "kind" to "block"),
            ),
        ),
        slots = mapOf(
            "root-slot" to Slot(id = "root-slot", nodeId = "root", name = "children", children = listOf("ph-main")),
            "ph-main-fill" to Slot(id = "ph-main-fill", nodeId = "ph-main", name = "fill", children = emptyList()),
        ),
        themeRef = ThemeRef.Inherit,
    )

    /**
     * Build a template body that embeds [stencilKey] once. The embedded subtree
     * mirrors what the editor would produce: the stencil node's slot contains
     * a placeholder named "body" whose fill carries one user-authored text node.
     */
    private fun templateEmbeddingStencilWithFilledPlaceholder(stencilKey: String): TemplateDocument = TemplateDocument(
        modelVersion = 1,
        root = "root",
        nodes = mapOf(
            "root" to Node(id = "root", type = "root", slots = listOf("root-slot")),
            "stencil-instance" to Node(
                id = "stencil-instance",
                type = "stencil",
                slots = listOf("stencil-children"),
                props = mapOf("stencilId" to stencilKey, "version" to 1),
            ),
            "embedded-ph" to Node(
                id = "embedded-ph",
                type = "placeholder",
                slots = listOf("embedded-ph-fill"),
                props = mapOf("name" to "body", "kind" to "block"),
            ),
            "user-fill-text" to Node(
                id = "user-fill-text",
                type = "text",
                slots = emptyList(),
                props = mapOf("content" to "User-authored body"),
            ),
        ),
        slots = mapOf(
            "root-slot" to Slot(id = "root-slot", nodeId = "root", name = "children", children = listOf("stencil-instance")),
            "stencil-children" to Slot(id = "stencil-children", nodeId = "stencil-instance", name = "children", children = listOf("embedded-ph")),
            "embedded-ph-fill" to Slot(id = "embedded-ph-fill", nodeId = "embedded-ph", name = "fill", children = listOf("user-fill-text")),
        ),
        themeRef = ThemeRef.Inherit,
    )

    // ---------------------------------------------------------------------------
    // Happy path — fill preservation across stencil version upgrade
    // ---------------------------------------------------------------------------

    @Test
    fun `stencil with placeholder publishes, embeds, fills, and the fill is preserved across rename upgrade`() = test {
        val tenant = createTenant("placeholder-flow")
        val tenantId = TenantId(tenant.id)
        val sId = stencilId(tenantId)

        // 1. Create stencil v1 with a placeholder; publish
        CreateStencil(id = sId, name = "Letter Body", content = stencilV1WithBodyPlaceholder()).execute()
        PublishStencilVersion(versionId = StencilVersionId(VersionKey.of(1), sId)).execute()

        // 2. Create a template that embeds the stencil with a user fill
        val templateKey = TestIdHelpers.nextTemplateId()
        val templateId = TemplateId(templateKey, CatalogId.default(tenantId))
        CreateDocumentTemplate(id = templateId, name = "Letter").execute()
        val variantId = VariantId(VariantKey.of("${templateKey.value}-default"), templateId)
        UpdateDraft(
            variantId = variantId,
            templateModel = templateEmbeddingStencilWithFilledPlaceholder(sId.key.value),
        ).execute()

        // 3. Author a v2 of the stencil that renames the placeholder; publish
        app.epistola.suite.stencils.commands.CreateStencilVersion(
            stencilId = sId,
            content = stencilV2RenamedPlaceholder(),
        ).execute()
        PublishStencilVersion(versionId = StencilVersionId(VersionKey.of(2), sId)).execute()

        // 4. Upgrade the embedded stencil instance to v2
        val result = UpdateStencilInTemplate(
            variantId = variantId,
            stencilId = sId,
            newVersion = 2,
        ).execute()

        // 5. The renamed placeholder did not match — fill is reported as dropped.
        assertThat(result).isNotNull
        assertThat(result!!.upgradedCount).isEqualTo(1)
        assertThat(result.droppedFills).hasSize(1)
        val dropped = result.droppedFills["stencil-instance"]
        assertThat(dropped).isNotNull
        assertThat(dropped!!).hasSize(1)
        assertThat(dropped[0].name).isEqualTo("body")
        assertThat(dropped[0].contentSummary).contains("User-authored body")
    }

    @Test
    fun `same-name placeholder preserves the user fill across upgrade`() = test {
        val tenant = createTenant("placeholder-preserve")
        val tenantId = TenantId(tenant.id)
        val sId = stencilId(tenantId)

        // v1 and v2 both declare placeholder "body" — only the default content changes.
        val v2SameName = TemplateDocument(
            modelVersion = 1,
            root = "root",
            nodes = mapOf(
                "root" to Node(id = "root", type = "root", slots = listOf("root-slot")),
                "ph-body" to Node(
                    id = "ph-body",
                    type = "placeholder",
                    slots = listOf("ph-body-fill"),
                    props = mapOf("name" to "body", "kind" to "block"),
                ),
                "v2-default-text" to Node(
                    id = "v2-default-text",
                    type = "text",
                    slots = emptyList(),
                    props = mapOf("content" to "v2 default"),
                ),
            ),
            slots = mapOf(
                "root-slot" to Slot(id = "root-slot", nodeId = "root", name = "children", children = listOf("ph-body")),
                "ph-body-fill" to Slot(id = "ph-body-fill", nodeId = "ph-body", name = "fill", children = listOf("v2-default-text")),
            ),
            themeRef = ThemeRef.Inherit,
        )

        CreateStencil(id = sId, name = "Header", content = stencilV1WithBodyPlaceholder()).execute()
        PublishStencilVersion(versionId = StencilVersionId(VersionKey.of(1), sId)).execute()

        val templateKey = TestIdHelpers.nextTemplateId()
        val templateId = TemplateId(templateKey, CatalogId.default(tenantId))
        CreateDocumentTemplate(id = templateId, name = "Letter").execute()
        val variantId = VariantId(VariantKey.of("${templateKey.value}-default"), templateId)
        UpdateDraft(
            variantId = variantId,
            templateModel = templateEmbeddingStencilWithFilledPlaceholder(sId.key.value),
        ).execute()

        app.epistola.suite.stencils.commands.CreateStencilVersion(stencilId = sId, content = v2SameName).execute()
        PublishStencilVersion(versionId = StencilVersionId(VersionKey.of(2), sId)).execute()

        val result = UpdateStencilInTemplate(
            variantId = variantId,
            stencilId = sId,
            newVersion = 2,
        ).execute()

        assertThat(result).isNotNull
        assertThat(result!!.upgradedCount).isEqualTo(1)
        // Same placeholder name → user fill preserved → no dropped fills.
        assertThat(result.droppedFills).isEmpty()
    }

    // ---------------------------------------------------------------------------
    // Validation wiring — server-side rejection
    // ---------------------------------------------------------------------------

    @Test
    fun `UpdateDraft rejects a recursive stencil document`() = test {
        val tenant = createTenant("placeholder-recursion")
        val tenantId = TenantId(tenant.id)

        val templateKey = TestIdHelpers.nextTemplateId()
        val templateId = TemplateId(templateKey, CatalogId.default(tenantId))
        CreateDocumentTemplate(id = templateId, name = "Recursive").execute()
        val variantId = VariantId(VariantKey.of("${templateKey.value}-default"), templateId)

        // root → stencil('header') → children → stencil('header') — recursion.
        val recursive = TemplateDocument(
            modelVersion = 1,
            root = "root",
            nodes = mapOf(
                "root" to Node(id = "root", type = "root", slots = listOf("root-slot")),
                "outer" to Node(
                    id = "outer",
                    type = "stencil",
                    slots = listOf("outer-slot"),
                    props = mapOf("stencilId" to "header", "version" to 1),
                ),
                "inner" to Node(
                    id = "inner",
                    type = "stencil",
                    slots = listOf("inner-slot"),
                    props = mapOf("stencilId" to "header", "version" to 1),
                ),
            ),
            slots = mapOf(
                "root-slot" to Slot(id = "root-slot", nodeId = "root", name = "children", children = listOf("outer")),
                "outer-slot" to Slot(id = "outer-slot", nodeId = "outer", name = "children", children = listOf("inner")),
                "inner-slot" to Slot(id = "inner-slot", nodeId = "inner", name = "children", children = emptyList()),
            ),
            themeRef = ThemeRef.Inherit,
        )

        assertThatThrownBy {
            UpdateDraft(variantId = variantId, templateModel = recursive).execute()
        }.isInstanceOf(ValidationException::class.java)
            .hasValidationCode(ValidationCode.STENCIL_RECURSION)
    }

    @Test
    fun `UpdateDraft rejects a placeholder without stencil ancestor`() = test {
        val tenant = createTenant("placeholder-bare")
        val tenantId = TenantId(tenant.id)

        val templateKey = TestIdHelpers.nextTemplateId()
        val templateId = TemplateId(templateKey, CatalogId.default(tenantId))
        CreateDocumentTemplate(id = templateId, name = "Bare placeholder").execute()
        val variantId = VariantId(VariantKey.of("${templateKey.value}-default"), templateId)

        // root → placeholder (illegal at template level — must be inside a stencil).
        val invalid = TemplateDocument(
            modelVersion = 1,
            root = "root",
            nodes = mapOf(
                "root" to Node(id = "root", type = "root", slots = listOf("root-slot")),
                "ph" to Node(
                    id = "ph",
                    type = "placeholder",
                    slots = listOf("ph-fill"),
                    props = mapOf("name" to "body", "kind" to "block"),
                ),
            ),
            slots = mapOf(
                "root-slot" to Slot(id = "root-slot", nodeId = "root", name = "children", children = listOf("ph")),
                "ph-fill" to Slot(id = "ph-fill", nodeId = "ph", name = "fill", children = emptyList()),
            ),
            themeRef = ThemeRef.Inherit,
        )

        assertThatThrownBy {
            UpdateDraft(variantId = variantId, templateModel = invalid).execute()
        }.isInstanceOf(ValidationException::class.java)
            .hasValidationCode(ValidationCode.PLACEHOLDER_OUTSIDE_STENCIL)
    }

    @Test
    fun `CreateStencil rejects content with duplicate placeholder names`() = test {
        val tenant = createTenant("placeholder-dup")
        val tenantId = TenantId(tenant.id)
        val sId = stencilId(tenantId)

        val duplicate = TemplateDocument(
            modelVersion = 1,
            root = "root",
            nodes = mapOf(
                "root" to Node(id = "root", type = "root", slots = listOf("root-slot")),
                "ph1" to Node(
                    id = "ph1",
                    type = "placeholder",
                    slots = listOf("ph1-fill"),
                    props = mapOf("name" to "body"),
                ),
                "ph2" to Node(
                    id = "ph2",
                    type = "placeholder",
                    slots = listOf("ph2-fill"),
                    props = mapOf("name" to "body"),
                ),
            ),
            slots = mapOf(
                "root-slot" to Slot(id = "root-slot", nodeId = "root", name = "children", children = listOf("ph1", "ph2")),
                "ph1-fill" to Slot(id = "ph1-fill", nodeId = "ph1", name = "fill", children = emptyList()),
                "ph2-fill" to Slot(id = "ph2-fill", nodeId = "ph2", name = "fill", children = emptyList()),
            ),
            themeRef = ThemeRef.Inherit,
        )

        assertThatThrownBy {
            CreateStencil(id = sId, name = "Dup", content = duplicate).execute()
        }.isInstanceOf(ValidationException::class.java)
            .hasValidationCode(ValidationCode.PLACEHOLDER_NAME_DUPLICATE)
    }

    @Test
    fun `template override in fill slot survives a stencil-edit-and-publish cycle`() = test {
        // Two-slot model: stencil v1 has placeholder with default in `default`
        // slot. Template embeds it and the user puts an override in `fill`.
        // We then create v2 with a *different* default and run the upgrade
        // (the same path Publish triggers from the inspector). The user's
        // fill override must survive; the new default must be installed.
        val tenant = createTenant("override-preserve")
        val tenantId = TenantId(tenant.id)
        val sId = stencilId(tenantId)

        val v1 = TemplateDocument(
            modelVersion = 1,
            root = "root",
            nodes = mapOf(
                "root" to Node(id = "root", type = "root", slots = listOf("root-slot")),
                "ph" to Node(
                    id = "ph",
                    type = "placeholder",
                    slots = listOf("ph-default", "ph-fill"),
                    props = mapOf("name" to "body", "kind" to "block"),
                ),
                "v1-default" to Node(
                    id = "v1-default",
                    type = "text",
                    slots = emptyList(),
                    props = mapOf("content" to "v1 default"),
                ),
            ),
            slots = mapOf(
                "root-slot" to Slot("root-slot", "root", "children", listOf("ph")),
                "ph-default" to Slot("ph-default", "ph", "default", listOf("v1-default")),
                "ph-fill" to Slot("ph-fill", "ph", "fill", emptyList()),
            ),
            themeRef = ThemeRef.Inherit,
        )

        CreateStencil(id = sId, name = "Letter Body", content = v1).execute()
        PublishStencilVersion(versionId = StencilVersionId(VersionKey.of(1), sId)).execute()

        // Template embeds the stencil and overrides the fill.
        val templateKey = TestIdHelpers.nextTemplateId()
        val templateId = TemplateId(templateKey, CatalogId.default(tenantId))
        CreateDocumentTemplate(id = templateId, name = "Letter").execute()
        val variantId = VariantId(VariantKey.of("${templateKey.value}-default"), templateId)

        val templateBody = TemplateDocument(
            modelVersion = 1,
            root = "root",
            nodes = mapOf(
                "root" to Node(id = "root", type = "root", slots = listOf("root-slot")),
                "stencil-instance" to Node(
                    id = "stencil-instance",
                    type = "stencil",
                    slots = listOf("stencil-children"),
                    props = mapOf("stencilId" to sId.key.value, "version" to 1),
                ),
                "embedded-ph" to Node(
                    id = "embedded-ph",
                    type = "placeholder",
                    slots = listOf("embedded-ph-default", "embedded-ph-fill"),
                    props = mapOf("name" to "body", "kind" to "block"),
                ),
                "embedded-default" to Node(
                    id = "embedded-default",
                    type = "text",
                    slots = emptyList(),
                    props = mapOf("content" to "v1 default"),
                ),
                "user-override" to Node(
                    id = "user-override",
                    type = "text",
                    slots = emptyList(),
                    props = mapOf("content" to "user override"),
                ),
            ),
            slots = mapOf(
                "root-slot" to Slot("root-slot", "root", "children", listOf("stencil-instance")),
                "stencil-children" to Slot(
                    "stencil-children",
                    "stencil-instance",
                    "children",
                    listOf("embedded-ph"),
                ),
                "embedded-ph-default" to Slot("embedded-ph-default", "embedded-ph", "default", listOf("embedded-default")),
                "embedded-ph-fill" to Slot("embedded-ph-fill", "embedded-ph", "fill", listOf("user-override")),
            ),
            themeRef = ThemeRef.Inherit,
        )
        UpdateDraft(variantId = variantId, templateModel = templateBody).execute()

        // Stencil author publishes v2 with a different default.
        val v2 = TemplateDocument(
            modelVersion = 1,
            root = "root",
            nodes = mapOf(
                "root" to Node(id = "root", type = "root", slots = listOf("root-slot")),
                "ph" to Node(
                    id = "ph",
                    type = "placeholder",
                    slots = listOf("ph-default", "ph-fill"),
                    props = mapOf("name" to "body", "kind" to "block"),
                ),
                "v2-default" to Node(
                    id = "v2-default",
                    type = "text",
                    slots = emptyList(),
                    props = mapOf("content" to "v2 default"),
                ),
            ),
            slots = mapOf(
                "root-slot" to Slot("root-slot", "root", "children", listOf("ph")),
                "ph-default" to Slot("ph-default", "ph", "default", listOf("v2-default")),
                "ph-fill" to Slot("ph-fill", "ph", "fill", emptyList()),
            ),
            themeRef = ThemeRef.Inherit,
        )
        app.epistola.suite.stencils.commands.CreateStencilVersion(
            stencilId = sId,
            content = v2,
        ).execute()
        PublishStencilVersion(versionId = StencilVersionId(VersionKey.of(2), sId)).execute()

        val result = UpdateStencilInTemplate(
            variantId = variantId,
            stencilId = sId,
            newVersion = 2,
        ).execute()

        assertThat(result).isNotNull
        assertThat(result!!.upgradedCount).isEqualTo(1)
        // Same placeholder name → user fill preserved → no dropped fills.
        assertThat(result.droppedFills).isEmpty()
    }

    @Test
    fun `CreateStencil rejects malformed parameterBindings prop`() = test {
        val tenant = createTenant("placeholder-binding-shape")
        val tenantId = TenantId(tenant.id)
        val sId = stencilId(tenantId)

        // parameterBindings as a non-map → caught by NODE_PARAMETER_BINDINGS_INVALID_SHAPE.
        val malformed = TemplateDocument(
            modelVersion = 1,
            root = "root",
            nodes = mapOf(
                "root" to Node(id = "root", type = "root", slots = listOf("root-slot")),
                "nested" to Node(
                    id = "nested",
                    type = "stencil",
                    slots = emptyList(),
                    props = mapOf(
                        "stencilId" to "other",
                        "version" to 1,
                        "parameterBindings" to "not-a-map",
                    ),
                ),
            ),
            slots = mapOf(
                "root-slot" to Slot(id = "root-slot", nodeId = "root", name = "children", children = listOf("nested")),
            ),
            themeRef = ThemeRef.Inherit,
        )

        assertThatThrownBy {
            CreateStencil(id = sId, name = "Malformed", content = malformed).execute()
        }.isInstanceOf(ValidationException::class.java)
            .hasValidationCode(ValidationCode.NODE_PARAMETER_BINDINGS_INVALID_SHAPE)
    }
}
