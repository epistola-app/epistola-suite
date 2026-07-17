package app.epistola.suite.quality

import app.epistola.suite.common.ids.CatalogId
import app.epistola.suite.common.ids.TemplateId
import app.epistola.suite.common.ids.TenantId
import app.epistola.suite.common.ids.VariantId
import app.epistola.suite.mediator.execute
import app.epistola.suite.mediator.query
import app.epistola.suite.quality.commands.IgnoreFinding
import app.epistola.suite.quality.commands.SubmitQualityFindings
import app.epistola.suite.quality.queries.ListQualityFindings
import app.epistola.suite.quality.queries.QualityFindingSort
import app.epistola.suite.templates.commands.CreateDocumentTemplate
import app.epistola.suite.templates.commands.variants.CreateVariant
import app.epistola.suite.testing.IntegrationTestBase
import app.epistola.suite.testing.TestIdHelpers
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * The tenant-wide report. Paging and filtering are pushed to the database, including the filter on
 * effective status — which is a *derived* value, and so the easiest thing in the feature to
 * accidentally do in Kotlin.
 */
class ListQualityFindingsIntegrationTest : IntegrationTestBase() {
    private val source = QualitySourceId("example")

    private fun finding(
        fingerprint: String,
        message: String = "something is off",
        severity: QualitySeverity = QualitySeverity.WARNING,
        ruleId: String = "example.rule",
    ) = SubmittedFinding(
        ruleId = ruleId,
        severity = severity,
        fingerprint = fingerprint,
        message = message,
    )

    private fun newSubject(name: String = "Invoice"): QualitySubject {
        val tenant = createTenant("Quality Report")
        return withMediator {
            val catalogId = CatalogId.default(TenantId(tenant.id))
            val templateId = TemplateId(TestIdHelpers.nextTemplateId(), catalogId)
            CreateDocumentTemplate(id = templateId, name = name).execute()
            val variantId = VariantId(TestIdHelpers.nextVariantId(), templateId)
            CreateVariant(id = variantId, title = "Default", description = null).execute()
            QualitySubject.of(variantId)
        }
    }

    @Test
    fun `the report lists findings with their total`() {
        val subject = newSubject()
        withMediator {
            SubmitQualityFindings(source, subject, (1..5).map { finding("fp-$it") }).execute()
        }

        val page = withMediator { ListQualityFindings(subject.tenantKey).query() }

        assertThat(page.items).hasSize(5)
        assertThat(page.total).isEqualTo(5)
    }

    /**
     * The test that actually enforces the no-in-memory-pagination rule.
     *
     * `IGNORED` is derived from a live ignore row, so filtering on it in Kotlin would mean fetching
     * every finding in the tenant to render one page — and would produce the *wrong window* here:
     * an implementation that pages first and filters second returns the ignored rows that happen to
     * fall in rows 2–3 of the unfiltered set, not the second page of ignored ones.
     */
    @Test
    fun `page two of an ignored filter returns the right window and total`() {
        val subject = newSubject()
        withMediator {
            SubmitQualityFindings(source, subject, (1..10).map { finding("fp-%02d".format(it)) }).execute()
        }

        // Ignore 6 of the 10, interleaved so a page-then-filter bug cannot pass by luck.
        val all = withMediator {
            ListQualityFindings(subject.tenantKey, sort = QualityFindingSort.RULE, descending = false, limit = 100).query()
        }.items.sortedBy { it.fingerprint }
        val ignored = listOf(0, 1, 3, 5, 7, 9).map { all[it] }
        withMediator { ignored.forEach { IgnoreFinding(subject.tenantKey, it.key, "not applicable").execute() } }

        val pageOne = withMediator {
            ListQualityFindings(
                subject.tenantKey,
                status = EffectiveQualityStatus.IGNORED,
                sort = QualityFindingSort.RULE,
                descending = false,
                limit = 4,
                offset = 0,
            ).query()
        }
        val pageTwo = withMediator {
            ListQualityFindings(
                subject.tenantKey,
                status = EffectiveQualityStatus.IGNORED,
                sort = QualityFindingSort.RULE,
                descending = false,
                limit = 4,
                offset = 4,
            ).query()
        }

        // The total counts the filtered set, not the tenant's findings.
        assertThat(pageOne.total).isEqualTo(6)
        assertThat(pageTwo.total).isEqualTo(6)
        assertThat(pageOne.items).hasSize(4)
        assertThat(pageTwo.items).hasSize(2)

        // Every returned row is genuinely ignored, and the two pages together are the whole set
        // with no overlap and nothing dropped.
        val returned = (pageOne.items + pageTwo.items)
        assertThat(returned).allSatisfy { assertThat(it.effectiveStatus).isEqualTo(EffectiveQualityStatus.IGNORED) }
        assertThat(returned.map { it.key }).doesNotHaveDuplicates()
        assertThat(returned.map { it.fingerprint })
            .containsExactlyInAnyOrderElementsOf(ignored.map { it.fingerprint })
    }

    @Test
    fun `the open filter excludes ignored and resolved findings`() {
        val subject = newSubject()
        withMediator {
            SubmitQualityFindings(source, subject, listOf(finding("fp-1"), finding("fp-2"), finding("fp-3"))).execute()
        }
        val all = withMediator { ListQualityFindings(subject.tenantKey).query() }.items.associateBy { it.fingerprint }
        withMediator { IgnoreFinding(subject.tenantKey, all["fp-2"]!!.key, "by design").execute() }
        // fp-3 disappears from the source's set, so it resolves.
        withMediator { SubmitQualityFindings(source, subject, listOf(finding("fp-1"), finding("fp-2"))).execute() }

        val open = withMediator { ListQualityFindings(subject.tenantKey, status = EffectiveQualityStatus.OPEN).query() }

        assertThat(open.total).isEqualTo(1)
        assertThat(open.items.single().fingerprint).isEqualTo("fp-1")
    }

    @Test
    fun `findings can be filtered by severity, source and rule`() {
        val subject = newSubject()
        val other = QualitySourceId("other")
        withMediator {
            SubmitQualityFindings(
                source,
                subject,
                listOf(
                    finding("fp-1", severity = QualitySeverity.ERROR, ruleId = "example.a"),
                    finding("fp-2", severity = QualitySeverity.INFO, ruleId = "example.b"),
                ),
            ).execute()
            SubmitQualityFindings(other, subject, listOf(finding("fp-3", severity = QualitySeverity.ERROR))).execute()
        }

        assertThat(withMediator { ListQualityFindings(subject.tenantKey, severity = QualitySeverity.ERROR).query() }.total)
            .isEqualTo(2)
        assertThat(withMediator { ListQualityFindings(subject.tenantKey, sourceId = other).query() }.total)
            .isEqualTo(1)
        assertThat(withMediator { ListQualityFindings(subject.tenantKey, ruleId = "example.b").query() }.total)
            .isEqualTo(1)
    }

    @Test
    fun `findings can be searched by message`() {
        val subject = newSubject()
        withMediator {
            SubmitQualityFindings(
                source,
                subject,
                listOf(finding("fp-1", message = "Heading is empty"), finding("fp-2", message = "Sentence is long")),
            ).execute()
        }

        val page = withMediator { ListQualityFindings(subject.tenantKey, searchTerm = "heading").query() }

        assertThat(page.items).singleElement().satisfies({ assertThat(it.fingerprint).isEqualTo("fp-1") })
    }

    /** Severity sorts by rank, not alphabetically — 'ERROR' < 'INFO' < 'WARNING' as plain text. */
    @Test
    fun `severity sorts worst-first rather than alphabetically`() {
        val subject = newSubject()
        withMediator {
            SubmitQualityFindings(
                source,
                subject,
                listOf(
                    finding("fp-info", severity = QualitySeverity.INFO),
                    finding("fp-error", severity = QualitySeverity.ERROR),
                    finding("fp-warning", severity = QualitySeverity.WARNING),
                ),
            ).execute()
        }

        val page = withMediator {
            ListQualityFindings(subject.tenantKey, sort = QualityFindingSort.SEVERITY, descending = false).query()
        }

        assertThat(page.items.map { it.severity })
            .containsExactly(QualitySeverity.ERROR, QualitySeverity.WARNING, QualitySeverity.INFO)
    }

    /** An unknown sort param falls back to the default rather than reaching the SQL. */
    @Test
    fun `an unrecognised sort param falls back to the default`() {
        assertThat(QualityFindingSort.fromParam("'; DROP TABLE quality_findings; --"))
            .isEqualTo(QualityFindingSort.LAST_SEEN)
        assertThat(QualityFindingSort.fromParam(null)).isEqualTo(QualityFindingSort.LAST_SEEN)
        assertThat(QualityFindingSort.fromParam("severity")).isEqualTo(QualityFindingSort.SEVERITY)
    }

    @Test
    fun `an out-of-range page returns no rows and a zero total`() {
        val subject = newSubject()
        withMediator { SubmitQualityFindings(source, subject, listOf(finding("fp-1"))).execute() }

        val page = withMediator { ListQualityFindings(subject.tenantKey, limit = 10, offset = 500).query() }

        assertThat(page.items).isEmpty()
        assertThat(page.total).isZero()
    }

    /** The report is tenant-scoped; another tenant's findings must never leak into it. */
    @Test
    fun `the report never returns another tenant's findings`() {
        val mine = newSubject()
        val theirs = newSubject("Their Invoice")
        withMediator { SubmitQualityFindings(source, mine, listOf(finding("fp-mine"))).execute() }
        withMediator { SubmitQualityFindings(source, theirs, listOf(finding("fp-theirs"))).execute() }

        val page = withMediator { ListQualityFindings(mine.tenantKey).query() }

        assertThat(page.total).isEqualTo(1)
        assertThat(page.items.single().fingerprint).isEqualTo("fp-mine")
    }
}
