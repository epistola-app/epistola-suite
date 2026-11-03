// SPDX-FileCopyrightText: Epistola Nederland B.V.
//
// SPDX-License-Identifier: AGPL-3.0-only

package app.epistola.suite.quality

import app.epistola.suite.catalog.bindCatalog
import app.epistola.suite.catalog.bindVersion
import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.QualityFindingKey
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.quality.queries.GetFindingsForSubject
import app.epistola.suite.quality.queries.GetQualityFinding
import app.epistola.suite.quality.queries.ListQualityFindings
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.variants.CreateVariant
import app.epistola.suite.templates.templateIn
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestIdHelpers
import app.epistola.suite.testing.default
import app.epistola.suite.testing.nextVersion
import app.epistola.suite.testing.testCatalog
import org.assertj.core.api.Assertions.assertThat
import org.jdbi.v3.core.Jdbi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * `severity` carries no CHECK on purpose, so the ledger has to survive a severity it does not know.
 *
 * That is not hypothetical for long: the REST ingest is where a remote source first gets to name its
 * own levels, and the failure it would otherwise cause is nasty — `valueOf` throws, so a single
 * unrecognised row takes out the entire report page rather than costing itself its label.
 */
class UnknownSeverityIntegrationTest : IntegrationTestBase() {

    @Autowired
    lateinit var jdbi: Jdbi

    private fun newSubject(): QualitySubject {
        val tenant = createTenant("Unknown Severity")
        return withMediator {
            val catalogId = CatalogId.default(TenantId(tenant.id))
            val templateId = TemplateId(TestIdHelpers.nextTemplateId(), catalogId)
            CreateDocumentTemplate(id = templateId, name = "Invoice", catalogVersion = null).execute()
            val variantId = VariantId(TestIdHelpers.nextVariantId(), templateId)
            CreateVariant(id = variantId, title = "Default", description = null, catalogVersion = null).execute()
            QualitySubject.of(variantId)
        }
    }

    /**
     * Raw SQL is the deliberate exception here: no command *can* produce this state, because
     * `SubmittedFinding.severity` is the [QualitySeverity] type — an in-process source cannot
     * express a severity outside the known set even to test it. The point is that the database
     * accepts one.
     */
    private fun insertFindingWithSeverity(
        subject: QualitySubject,
        severity: String,
        fingerprint: String = "fp-$severity",
    ): QualityFindingKey {
        val key = QualityFindingKey.generate()
        // The check the row belongs to, through the command: an empty answer of the remote checker,
        // given once, since a second answer would replace the rows inserted under the first.
        val read = withMediator { GetFindingsForSubject(subject.catalogId, subject.templateKey, subject.variantKey!!, catalogVersion = null).query() }
        if (read.checks.isEmpty()) withMediator { SubmitQualityFindings(QualitySourceId("remote-checker"), subject, emptyList()).execute() }
        val inputKey = read.currentInputKey
        jdbi.useHandle<Exception> { handle ->
            handle.createUpdate(
                """
                INSERT INTO quality_findings (
                    tenant_key, id, source_id, rule_id, severity, subject_urn, subject_type,
                    ignore_scope_urn, template_resource_id, variant_key, node_ids,
                    message, fingerprint, input_key, context, status, first_seen_at, last_seen_at
                ) VALUES (
                    :tenantKey, :id, 'remote-checker', 'remote.rule', :severity, :subjectUrn, :subjectType,
                    :ignoreScopeUrn, ${templateIn("tenantKey", "catalogKey", "templateKey", "v")}, :variantKey, '{}',
                    'A remote checker used its own vocabulary', :fingerprint, :inputKey, '{}'::jsonb, 'OPEN', NOW(), NOW()
                )
                """,
            )
                .bind("tenantKey", subject.tenantKey)
                .bind("id", key.value)
                .bind("severity", severity)
                .bind("fingerprint", fingerprint)
                .bind("inputKey", inputKey)
                .bind("subjectUrn", subject.urn)
                .bind("subjectType", subject.type.name)
                .bind("ignoreScopeUrn", subject.ignoreScopeUrn)
                .bindCatalog("catalogKey", testCatalog(subject.tenantKey, subject.catalogKey))
                .bindVersion("v", nextVersion(testCatalog(subject.tenantKey, subject.catalogKey)).versionId)
                .bind("templateKey", subject.templateKey)
                .bind("variantKey", subject.variantKey)
                .execute()
        }
        return key
    }

    /** The schema must not be the thing that stops a source defining its own levels. */
    @Test
    fun `the database accepts a severity outside the known set`() {
        val subject = newSubject()

        val key = insertFindingWithSeverity(subject, "CRITICAL")

        assertThat(key).isNotNull
    }

    @Test
    fun `reading one finding with an unknown severity does not throw`() {
        val subject = newSubject()
        val key = insertFindingWithSeverity(subject, "CRITICAL")

        val finding = withMediator { GetQualityFinding(subject.tenantKey, key).query() }

        assertThat(finding).isNotNull
        assertThat(finding!!.message).isEqualTo("A remote checker used its own vocabulary")
        // Coerced to the honest middle — INFO would hide what a source thought urgent, ERROR
        // would cry wolf about what it did not.
        assertThat(finding.severity).isEqualTo(QualitySeverity.WARNING)
    }

    /** The failure that matters: one odd row must not cost the reader the whole report. */
    @Test
    fun `an unknown severity does not take out the report page`() {
        val subject = newSubject()
        insertFindingWithSeverity(subject, "CRITICAL")
        insertFindingWithSeverity(subject, "HINT")

        val page = withMediator { ListQualityFindings(subject.tenantKey, catalogVersion = null).query() }

        assertThat(page.total).isEqualTo(2)
        assertThat(page.items).hasSize(2)
    }
}
