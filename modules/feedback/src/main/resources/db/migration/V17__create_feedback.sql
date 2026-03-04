-- ============================================================================
-- FEEDBACK SYNC CONFIGURATION (per-tenant sync provider settings)
-- ============================================================================

CREATE TABLE feedback_sync_config (
    tenant_key      VARCHAR(63) NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    enabled         BOOLEAN     NOT NULL DEFAULT false,
    provider_type   VARCHAR(30) NOT NULL,
    settings        JSONB       NOT NULL DEFAULT '{}',
    last_polled_at  TIMESTAMP WITH TIME ZONE,

    PRIMARY KEY (tenant_key)
);

COMMENT ON TABLE feedback_sync_config IS 'Per-tenant configuration for external issue tracker sync';
COMMENT ON COLUMN feedback_sync_config.provider_type IS 'Sync provider type (GITHUB, JIRA, etc.)';
COMMENT ON COLUMN feedback_sync_config.settings IS 'Provider-specific settings as JSONB (e.g., repo, label, installation ID)';
COMMENT ON COLUMN feedback_sync_config.last_polled_at IS 'Timestamp of last successful inbound poll';

-- ============================================================================
-- FEEDBACK (core feedback items)
-- ============================================================================

CREATE TABLE feedback (
    tenant_key      VARCHAR(63) NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    id              UUID        NOT NULL,
    title           TEXT        NOT NULL,
    description     TEXT        NOT NULL,
    category        VARCHAR(30) NOT NULL,
    status          VARCHAR(30) NOT NULL DEFAULT 'OPEN',
    priority        VARCHAR(30) NOT NULL DEFAULT 'MEDIUM',
    source_url      TEXT,
    screenshot_key  UUID,        -- soft reference to assets table
    console_logs    TEXT,
    created_by      UUID        NOT NULL REFERENCES users(id),
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    external_ref    TEXT,        -- external ticket reference (e.g., GitHub issue number)
    external_url    TEXT,        -- external ticket URL
    sync_status     VARCHAR(30) NOT NULL DEFAULT 'NOT_CONFIGURED',

    PRIMARY KEY (tenant_key, id)
);

CREATE INDEX idx_feedback_status ON feedback(tenant_key, status);
CREATE INDEX idx_feedback_created_at ON feedback(tenant_key, created_at DESC);
CREATE INDEX idx_feedback_created_by ON feedback(tenant_key, created_by);
CREATE INDEX idx_feedback_sync_status ON feedback(sync_status) WHERE sync_status = 'PENDING';
CREATE INDEX idx_feedback_external_ref ON feedback(tenant_key, external_ref) WHERE external_ref IS NOT NULL;

COMMENT ON TABLE feedback IS 'User-submitted feedback items (bugs, feature requests, questions)';
COMMENT ON COLUMN feedback.screenshot_key IS 'Soft reference to assets.id — no FK to avoid cross-concern coupling';
COMMENT ON COLUMN feedback.external_ref IS 'External ticket reference after sync (e.g., GitHub issue number)';
COMMENT ON COLUMN feedback.external_url IS 'External ticket URL after sync';
COMMENT ON COLUMN feedback.sync_status IS 'PENDING, SYNCED, FAILED, or NOT_CONFIGURED';

-- ============================================================================
-- FEEDBACK COMMENTS
-- ============================================================================

CREATE TABLE feedback_comments (
    tenant_key          VARCHAR(63) NOT NULL,
    feedback_id         UUID        NOT NULL,
    id                  UUID        NOT NULL,
    body                TEXT        NOT NULL,
    author_name         TEXT        NOT NULL,
    author_email        TEXT,
    source              VARCHAR(20) NOT NULL DEFAULT 'LOCAL',
    external_comment_id TEXT,       -- external comment ID for dedup (string to support GitHub, Jira, etc.)
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

    PRIMARY KEY (tenant_key, feedback_id, id),
    FOREIGN KEY (tenant_key, feedback_id) REFERENCES feedback(tenant_key, id) ON DELETE CASCADE
);

-- Unique index for dedup of external comments
CREATE UNIQUE INDEX idx_feedback_comments_external
    ON feedback_comments(tenant_key, external_comment_id)
    WHERE external_comment_id IS NOT NULL;

COMMENT ON TABLE feedback_comments IS 'Comments on feedback items, from local users or synced from external trackers';
COMMENT ON COLUMN feedback_comments.source IS 'LOCAL (user via UI) or EXTERNAL (synced from external tracker)';
COMMENT ON COLUMN feedback_comments.external_comment_id IS 'External comment ID used for dedup during sync';
