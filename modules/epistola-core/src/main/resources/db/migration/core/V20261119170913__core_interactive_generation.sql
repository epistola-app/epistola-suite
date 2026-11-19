-- Interactive generation (contract ADR 0011, alpha): a generation request may wait for a person to
-- complete its data in a form before it renders.
--
-- A request that waits is a row in document_generation_requests with status AWAITING_INPUT. The
-- poller and stale-job recovery select only PENDING and IN_PROGRESS rows, so it is never claimed
-- until the form moves it to PENDING. What the form needs (its link, the caller's texts, the data
-- as the caller sent it) lives in generation_request_interactions, partitioned on the same
-- created_at as its request so retention drops both together.
--
-- rollback-compatible: false
-- reason: Requests may now hold status AWAITING_INPUT and CANCELLED results may be emitted, which
--   the previous release's status enums cannot read: listing such a job would fail there.

-- ---------------------------------------------------------------------------------------------
-- Requests: the new status, why a request was cancelled, and when its form was submitted.
-- ---------------------------------------------------------------------------------------------

ALTER TABLE document_generation_requests
    DROP CONSTRAINT document_generation_requests_status_check,
    ADD CONSTRAINT document_generation_requests_status_check
        CHECK (status IN ('AWAITING_INPUT', 'PENDING', 'IN_PROGRESS', 'COMPLETED', 'FAILED', 'CANCELLED')),
    ADD COLUMN cancel_reason VARCHAR(16)
        CONSTRAINT chk_requests_cancel_reason CHECK (cancel_reason IN ('CALLER', 'USER', 'EXPIRED')),
    ADD COLUMN submitted_at TIMESTAMPTZ;

COMMENT ON COLUMN document_generation_requests.status IS 'Job state: (AWAITING_INPUT ->) PENDING -> IN_PROGRESS -> COMPLETED/FAILED/CANCELLED';
COMMENT ON COLUMN document_generation_requests.cancel_reason IS 'Why a CANCELLED request was cancelled: CALLER (cancelGenerationJob), USER (on the form) or EXPIRED (the form was not submitted in time). NULL otherwise, and for requests cancelled before interactive generation existed.';
COMMENT ON COLUMN document_generation_requests.submitted_at IS 'When the person submitted the request''s form, moving it from AWAITING_INPUT to PENDING. NULL for requests that never waited.';

-- ---------------------------------------------------------------------------------------------
-- The form of a request created with `interactive`.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE generation_request_interactions (
    request_id      UUID        NOT NULL,
    -- The request's created_at: the partition key, so a form is dropped with its request.
    created_at      TIMESTAMPTZ NOT NULL,
    tenant_key      TENANT_KEY  NOT NULL,
    trigger         VARCHAR(16) NOT NULL CONSTRAINT chk_interactions_trigger CHECK (trigger IN ('INCOMPLETE', 'ALWAYS')),
    -- The link's token: its SHA-256 (hex) to find the form, and the token itself encrypted
    -- (credential encryption) so the link can be handed out again while the request waits. NULL
    -- when nothing was asked.
    token_hash      CHAR(64),
    token_encrypted TEXT,
    expires_at      TIMESTAMPTZ,
    return_url      TEXT,
    title           TEXT,
    instructions    TEXT,
    links           JSONB       NOT NULL DEFAULT '[]'::jsonb,
    locale          VARCHAR(35),
    -- What the form asks for, as the analysis reported it when the request was accepted.
    missing_fields  JSONB       NOT NULL DEFAULT '[]'::jsonb,
    -- The data exactly as the caller sent it; the request's own `data` becomes the submitted data.
    caller_data     JSONB       NOT NULL,
    -- JSON Pointers where the submitted data differs from caller_data. NULL until submitted.
    edited_fields   JSONB,
    PRIMARY KEY (request_id, created_at),
    CONSTRAINT chk_interactions_token CHECK ((token_hash IS NULL) = (token_encrypted IS NULL)),
    CONSTRAINT chk_interactions_token_expiry CHECK (token_hash IS NULL OR expires_at IS NOT NULL)
) PARTITION BY RANGE (created_at);

-- The form page finds its request by the token's hash.
CREATE INDEX idx_interactions_token_hash ON generation_request_interactions (token_hash) WHERE token_hash IS NOT NULL;

-- No initial partitions: PartitionMaintenanceScheduler creates the current and next month at
-- startup, as for document_content.

COMMENT ON TABLE generation_request_interactions IS 'The form of a generation request created with interactive (contract ADR 0011, alpha). Partitioned on its request''s created_at and dropped with it by retention. Holds personal data: the caller''s texts and data.';

-- ---------------------------------------------------------------------------------------------
-- Results: a CANCELLED line for a request with a form, and why.
-- ---------------------------------------------------------------------------------------------

ALTER DOMAIN GENERATION_RESULT_STATUS DROP CONSTRAINT generation_result_status_check;
ALTER DOMAIN GENERATION_RESULT_STATUS ADD CONSTRAINT generation_result_status_check
    CHECK (VALUE IN ('COMPLETED', 'FAILED', 'CANCELLED'));

ALTER TABLE generation_results
    ADD COLUMN cancel_reason VARCHAR(16)
        CONSTRAINT chk_results_cancel_reason CHECK (cancel_reason IN ('CALLER', 'USER', 'EXPIRED')),
    ADD COLUMN data JSONB,
    ADD COLUMN edited_fields JSONB;

COMMENT ON COLUMN generation_results.cancel_reason IS 'Why a CANCELLED result''s request was cancelled; NULL for other results.';
COMMENT ON COLUMN generation_results.data IS 'For a request with a form, the data it rendered from, copied when the result was emitted so collect needs no join; NULL on CANCELLED results and for requests without a form. Personal data, kept as long as the result.';
COMMENT ON COLUMN generation_results.edited_fields IS 'JSON Pointers where data differs from what the caller sent; set and NULL exactly when data is.';

-- ---------------------------------------------------------------------------------------------
-- Applications: where a form may send the browser back to.
-- ---------------------------------------------------------------------------------------------

ALTER TABLE applications
    ADD COLUMN allowed_return_origins TEXT[] NOT NULL DEFAULT '{}';

COMMENT ON COLUMN applications.allowed_return_origins IS 'Origins (scheme://host[:port]) an interactive generation request of this application may name as returnUrl or link. Matched exactly.';
