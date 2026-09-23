-- backup-restore-compatibility: backward=true forward=true
-- reason: Adds tables holding immutable, content-addressed copies of released resources. A backup
-- from either side restores; an older reader ignores them, and they are rewritable while nothing
-- reads them.
-- SPDX-FileCopyrightText: Epistola Nederland B.V.
--
-- SPDX-License-Identifier: AGPL-3.0-only

-- Retain the content behind a release, not only its digest.
--
-- A release records which resources it contained and at which digest (V20260923154857), but not the
-- content behind them, so an earlier release cannot be reproduced, exported or rendered from -- it
-- is a fingerprint and a promise. Content that does not move when the working copy does is what
-- retained releases, self-contained artifacts and deploying a release to an environment all rest on.
--
-- Content-addressed and deduplicated: re-releasing a catalog whose one changed template is the only
-- edit writes one payload and reuses the rest. Scoped by tenant, so identical content is stored once
-- within a tenant and never shared across them.

-- Extend by inserting a row, not by widening a CHECK.
--
-- Deliberately not `catalog_resource_types`, which holds the catalog wire's own tokens so that a
-- registry address is the triple an export uses. A revision kind is a storage concept, and it
-- includes parts of a resource that the wire never names on their own.
CREATE TABLE resource_revision_kinds (
    kind VARCHAR(20) PRIMARY KEY
);

COMMENT ON TABLE resource_revision_kinds IS
    'What a stored revision is: a catalog wire resource type, or a part of one that is stored separately.';

INSERT INTO resource_revision_kinds (kind) VALUES
    ('codeList'), ('font'), ('attribute'), ('theme'), ('stencil'), ('image'), ('template'),
    -- One variant's model. A bundled template is 20-70 KB of JSON, and putting every variant in one
    -- payload would rewrite all of them whenever one changes (ADR 0026 section 3a).
    ('templateModel');

CREATE TABLE resource_revisions (
    tenant_key TENANT_KEY  NOT NULL,
    digest     CHAR(64)    NOT NULL,
    kind       VARCHAR(20) NOT NULL,
    payload    JSONB       NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_resource_revisions PRIMARY KEY (tenant_key, digest),
    CONSTRAINT fk_resource_revisions_tenant FOREIGN KEY (tenant_key)
        REFERENCES tenants (id) ON DELETE CASCADE,
    CONSTRAINT fk_resource_revisions_kind FOREIGN KEY (kind)
        REFERENCES resource_revision_kinds (kind)
);

COMMENT ON TABLE resource_revisions IS
    'Immutable content of one released resource, or one part of one, deduplicated by digest within a tenant. Written at release; never updated.';
COMMENT ON COLUMN resource_revisions.digest IS
    'Lowercase hex SHA-256 of the canonical payload. Internal storage identity -- NOT the catalog fingerprint, which the contract computes over the wire form and which appears on the wire (ADR 0026 section 5).';
COMMENT ON COLUMN resource_revisions.payload IS
    'The resource in its protocol form. A template payload carries a digest reference in place of each variant''s model, so the parent digest still covers the whole.';

-- The bytes a revision needs, and the reason they are not collected.
--
-- Releasing a catalog and then deleting an image from the working copy must not collect the bytes
-- the release still names. `ContentReaper` sweeps `asset_content` rows that no live `assets` row
-- points at; these rows are the second root, and the foreign key makes that a fact of the schema
-- rather than a rule someone has to remember.
--
-- `scope` is part of the key because it is part of `asset_content`'s: a sensitive asset's bytes are
-- stored per tenant and a shared one's under 'global', so a hash alone does not resolve to bytes.
-- No media type: `asset_content.content_type` already holds it, and a second copy is a second thing
-- to keep in step.
CREATE TABLE revision_binaries (
    tenant_key   TENANT_KEY NOT NULL,
    digest       CHAR(64)   NOT NULL,
    scope        TEXT       NOT NULL,
    content_hash TEXT       NOT NULL,
    CONSTRAINT pk_revision_binaries PRIMARY KEY (tenant_key, digest, scope, content_hash),
    CONSTRAINT fk_revision_binaries_revision FOREIGN KEY (tenant_key, digest)
        REFERENCES resource_revisions (tenant_key, digest) ON DELETE CASCADE,
    CONSTRAINT fk_revision_binaries_content FOREIGN KEY (scope, content_hash)
        REFERENCES asset_content (scope, content_hash)
);

COMMENT ON TABLE revision_binaries IS
    'Content-store bytes a revision needs. Retention roots: bytes reachable from a revision are never collected, and the foreign key enforces it.';

-- Serves the sweep, which asks whether anything still holds a given blob.
CREATE INDEX idx_revision_binaries_content ON revision_binaries (scope, content_hash);
