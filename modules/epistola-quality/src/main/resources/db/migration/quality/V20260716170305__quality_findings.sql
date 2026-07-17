-- backup-restore-compatibility: backward=true forward=false
-- reason: Additive — creates quality_findings / quality_finding_ignores /
-- quality_finding_comments, which ARE backed up (CoreBackupTables.includedTables).
-- backward=true: an older backup predates these tables, lists none of them in its
-- manifest, and merge-restores cleanly into this newer schema.
-- forward=false: a backup taken at/after this stamp lists tables an older app does not
-- have, and RestoreTenantBackup.validateColumns rejects a manifest table absent from the
-- live topology — so a forward restore across this migration cannot succeed and must not
-- be declared compatible. (Contrast V20260622102813__audit_audit_log, which declares
-- forward=true only because audit_log is EXCLUDED from backup.)
--
-- ============================================================================
-- QUALITY CHECKS — a findings ledger
-- ============================================================================
--
-- Quality findings are problems reported about a template: readability, grammar,
-- cross-template consistency, version-compatibility drift, or a human review note.
-- Checks are NOT run here. This is a ledger: sources SUBMIT their findings and the
-- ledger owns them from then on. A source may execute in-process (a Spring bean
-- implementing QualityFindingSource, run by the framework) or remotely (pushing over
-- the REST ingest on its own schedule); both converge on the SubmitQualityFindings
-- command, so reconciliation, ignores, and staleness behave identically either way.
--
-- Checks only ever analyse a template's EXAMPLE data (contract_versions.data_examples),
-- never user data.
--
-- Reconciliation: a source submits its FULL current finding set for a subject. New
-- fingerprints appear, absent ones auto-RESOLVE, unchanged ones keep their ignore.
-- That gives resolution-on-fix with no source having to track deltas.

CREATE TABLE quality_findings (
    tenant_key        TENANT_KEY   NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    id                UUID         NOT NULL,   -- UUIDv7; stable local identity — comments hang off it and survive resolve/resurface
    source_id         VARCHAR(64)  NOT NULL,
    rule_id           VARCHAR(128) NOT NULL,
    severity          VARCHAR(16)  NOT NULL,
    subject_urn       TEXT         NOT NULL,   -- EntityIdBase.toUrn() of what was analysed
    subject_type      VARCHAR(32)  NOT NULL,
    ignore_scope_urn  TEXT         NOT NULL,   -- what an ignore attaches to; derived from the subject at submit
    -- Flat keys denormalized from the subject URN at submit, for cheap filtering and
    -- ON DELETE CASCADE-adjacent reporting. NOT NULL in v1 because sources are
    -- constrained to template-family subjects; relaxing that is a cheap ALTER that does
    -- not change the column set (so it stays restore-safe).
    catalog_key       CATALOG_KEY  NOT NULL,
    template_key      TEMPLATE_KEY NOT NULL,
    variant_key       VARIANT_KEY,
    version_key       INT,
    node_ids          TEXT[]       NOT NULL DEFAULT '{}',  -- editor nodes this finding points at; drives the in-editor markers
    path              TEXT,                    -- JSON pointer / data path, when it has one
    message           TEXT         NOT NULL,
    docs_url          TEXT,
    fingerprint       VARCHAR(128) NOT NULL,
    input_fingerprint VARCHAR(64),
    context           JSONB        NOT NULL DEFAULT '{}',
    status            VARCHAR(16)  NOT NULL DEFAULT 'OPEN',
    first_seen_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    last_seen_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    resolved_at       TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    PRIMARY KEY (tenant_key, id),
    CONSTRAINT quality_findings_severity_check CHECK (severity IN ('INFO', 'WARNING', 'ERROR')),
    CONSTRAINT quality_findings_status_check CHECK (status IN ('OPEN', 'RESOLVED')),
    CONSTRAINT quality_findings_resolved_at_check CHECK ((status = 'RESOLVED') = (resolved_at IS NOT NULL))
);

-- The reconciliation key. One row per (source, subject, fingerprint) — forever. The
-- submit upsert targets this, so a resurfacing finding reuses its original row id.
CREATE UNIQUE INDEX uq_quality_findings_reconcile
    ON quality_findings(tenant_key, source_id, subject_urn, fingerprint);

-- Editor panel: the open findings for one subject.
CREATE INDEX idx_quality_findings_subject ON quality_findings(tenant_key, subject_urn) WHERE status = 'OPEN';
-- Report: filter by template.
CREATE INDEX idx_quality_findings_template ON quality_findings(tenant_key, catalog_key, template_key);
-- Report: the default listing (newest-seen first, filtered by status/severity).
CREATE INDEX idx_quality_findings_report ON quality_findings(tenant_key, status, severity, last_seen_at DESC);
-- Ignore join + per-source reporting.
CREATE INDEX idx_quality_findings_ignore_scope ON quality_findings(tenant_key, ignore_scope_urn, source_id);
-- "Which findings touch this node" — the editor asks per subject and fans out client-side, but a
-- node-anchored lookup is cheap to support and this is the index it needs.
CREATE INDEX idx_quality_findings_node_ids ON quality_findings USING GIN (node_ids);

COMMENT ON TABLE quality_findings IS 'Ledger of quality findings submitted by check sources (in-process or remote) and by humans. Checks are not run here — sources submit, the ledger owns. Reconciled per (source, subject): a submission is the source''s FULL current set, and anything absent from it auto-resolves.';
COMMENT ON COLUMN quality_findings.id IS 'UUIDv7 — stable local identity. Deliberately preserved across resolve/resurface so comments survive the cycle.';
COMMENT ON COLUMN quality_findings.source_id IS 'Who reported this. Reconciliation is scoped by it, so one source can never resolve another''s findings (nor the reserved "manual" source''s).';
COMMENT ON COLUMN quality_findings.rule_id IS 'Source-defined rule identifier. Findings are self-describing — there is deliberately no local rule catalog table to drift from a remote source''s rules.';
COMMENT ON COLUMN quality_findings.subject_urn IS 'EntityIdBase.toUrn() of what the source analysed (template / variant / version / contract-version).';
COMMENT ON COLUMN quality_findings.ignore_scope_urn IS 'What an ignore attaches to — deliberately coarser than subject_urn (a template URN for a finding on one of its versions), so an ignore carries forward across versions instead of being re-applied after every publish. Generalized to a URN rather than flat (catalog, template) columns so a future tenant-scoped finding (e.g. a compatibility verdict) fits the same key.';
COMMENT ON COLUMN quality_findings.node_ids IS 'The editor nodes this finding is about, in the source''s own order of relevance; the editor marks each and navigates to the first. A LIST, not a single node, because a finding is often genuinely about several elements at once — "these two paragraphs contradict each other", "these blocks disagree on date format". Emitting one finding per node instead would split a single problem into several that ignore and resolve independently. Empty when the finding is not about any particular element (a document-level or data-level observation).';
COMMENT ON COLUMN quality_findings.fingerprint IS 'Source-computed finding identity — OPAQUE to the ledger (no CHECK; a remote source need not use hex sha256). Contract: stable across re-runs for the same problem, changes when the problem materially changes. Auto-resolve and ignore-carry-forward both rest on it.';
COMMENT ON COLUMN quality_findings.input_fingerprint IS 'LEDGER-computed hash of the template model this finding was computed against — drives the editor''s "outdated" marker. Deliberately not source-supplied: Postgres normalizes jsonb key order, so a remote source hashing the JSON it fetched would produce a different string and every one of its findings would read as permanently stale.';
COMMENT ON COLUMN quality_findings.context IS 'Source-supplied structured evidence (e.g. {"length": 142}). Free-form; the ledger never interprets it.';
COMMENT ON COLUMN quality_findings.status IS 'OPEN or RESOLVED only. IGNORED is NOT a status — it is DERIVED from a live quality_finding_ignores row. That keeps "an unchanged fingerprint retains its ignore" true by construction, with no dual-write to diverge.';
COMMENT ON COLUMN quality_findings.first_seen_at IS 'First time any source reported this fingerprint. Never updated by a resubmit.';
COMMENT ON COLUMN quality_findings.last_seen_at IS 'Most recent submission that still reported this fingerprint.';

-- ============================================================================
-- QUALITY FINDING IGNORES
-- ============================================================================
--
-- The only user-authored state a source cares about: "this is a false positive, because…".
-- Deliberately NOT stored in the template model (TemplateDocument is a generated type from
-- the external @epistola.app/epistola-model package, and published versions are frozen —
-- so an ignore there could never be applied to a published version).

CREATE TABLE quality_finding_ignores (
    tenant_key          TENANT_KEY   NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    ignore_scope_urn    TEXT         NOT NULL,
    source_id           VARCHAR(64)  NOT NULL,
    rule_id             VARCHAR(128) NOT NULL,
    finding_fingerprint VARCHAR(128) NOT NULL,
    reason              TEXT         NOT NULL,
    ignored_by          UUID         REFERENCES users(id) ON DELETE SET NULL,
    ignored_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    revoked_by          UUID         REFERENCES users(id) ON DELETE SET NULL,
    revoked_at          TIMESTAMPTZ,
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    PRIMARY KEY (tenant_key, ignore_scope_urn, source_id, rule_id, finding_fingerprint)
);

-- The disposition feed: sources pull "what did humans mark irrelevant" with a `since`
-- cursor, ordered by updated_at. Covers both ignore and revoke.
CREATE INDEX idx_quality_ignores_disposition ON quality_finding_ignores(tenant_key, source_id, updated_at DESC);

COMMENT ON TABLE quality_finding_ignores IS 'Human dispositions on findings ("false positive, because…"). Deliberately has NO foreign key to quality_findings: an ignore must be able to outlive its finding (which auto-resolves) and to pre-exist a resurface, which is exactly what makes carry-forward work.';
COMMENT ON COLUMN quality_finding_ignores.ignore_scope_urn IS 'What this ignore attaches to (matches quality_findings.ignore_scope_urn). Deliberately NO version in the key — an ignore carries forward across versions; a materially changed finding gets a new fingerprint and resurfaces for review on its own.';
COMMENT ON COLUMN quality_finding_ignores.finding_fingerprint IS 'The source-computed fingerprint being ignored. Same fingerprint recurring => still ignored; a changed one => resurfaces.';
COMMENT ON COLUMN quality_finding_ignores.revoked_at IS 'Soft delete. NOT decoration: a hard DELETE would be invisible to the `since` cursor of GetFindingDispositions, so a source would either treat the finding as ignored forever or have to full-scan every cycle. Set on unignore; cleared when re-ignored.';
COMMENT ON COLUMN quality_finding_ignores.updated_at IS 'Monotonic across both ignore and revoke (via the shared set_updated_at() trigger), which is what makes it a correct disposition cursor.';

-- ============================================================================
-- QUALITY FINDING COMMENTS
-- ============================================================================
--
-- LOCAL ONLY in v1. Deliberately no source/external_comment_id columns (feedback_comments
-- has them because it syncs) — what a comment means to a remote checker is unsettled, so
-- nothing crosses the hub boundary yet. The stable finding id is all a future sync needs.

CREATE TABLE quality_finding_comments (
    tenant_key  TENANT_KEY  NOT NULL,
    finding_id  UUID        NOT NULL,
    id          UUID        NOT NULL,
    body        TEXT        NOT NULL,
    author_id   UUID        REFERENCES users(id) ON DELETE SET NULL,
    author_name TEXT        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    PRIMARY KEY (tenant_key, finding_id, id),
    FOREIGN KEY (tenant_key, finding_id) REFERENCES quality_findings(tenant_key, id) ON DELETE CASCADE
);

COMMENT ON TABLE quality_finding_comments IS 'Discussion on a finding (e.g. a reviewer explaining what they want changed). Local-only in v1 — never synced.';
COMMENT ON COLUMN quality_finding_comments.author_name IS 'Denormalized at write time so a comment still renders after the user is deleted (author_id then resolves to NULL). Mirrors feedback_comments.';

-- updated_at is DB-enforced by the shared set_updated_at() trigger function.
CREATE TRIGGER trg_quality_findings_updated_at
    BEFORE UPDATE ON quality_findings
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_quality_finding_ignores_updated_at
    BEFORE UPDATE ON quality_finding_ignores
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
