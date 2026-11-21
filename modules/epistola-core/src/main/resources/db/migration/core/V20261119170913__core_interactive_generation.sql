-- Interactive generation (contract ADR 0011, alpha): a generation request may wait for a person to
-- complete its data in a form before it renders.
--
-- A request that waits is a row in document_generation_requests with status AWAITING_INPUT. The
-- poller and stale-job recovery select only PENDING and IN_PROGRESS rows, so it is never claimed
-- until the form moves it to PENDING. What the form needs (the caller's texts, the data as the
-- caller sent it) lives in generation_request_interactions, partitioned on the same created_at as
-- its request so retention drops both together. Links to the form are short-lived signed codes
-- (epistola-contract#134); only a redeemed code's nonce is kept, until it would have expired.
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
    ADD COLUMN submitted_at TIMESTAMPTZ,
    ADD COLUMN has_form BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN document_generation_requests.status IS 'Job state: (AWAITING_INPUT ->) PENDING -> IN_PROGRESS -> COMPLETED/FAILED/CANCELLED';
COMMENT ON COLUMN document_generation_requests.cancel_reason IS 'Why a CANCELLED request was cancelled: CALLER (cancelGenerationJob), USER (on the form) or EXPIRED (the form was not submitted in time). NULL otherwise, and for requests cancelled before interactive generation existed.';
COMMENT ON COLUMN document_generation_requests.submitted_at IS 'When the person submitted the request''s form, moving it from AWAITING_INPUT to PENDING. NULL for requests that never waited.';
COMMENT ON COLUMN document_generation_requests.has_form IS 'Whether the request was created with interactive and so has a row in generation_request_interactions. Lets every other request skip that table (its lookups by request id probe every partition).';

-- ---------------------------------------------------------------------------------------------
-- The form of a request created with `interactive`.
-- ---------------------------------------------------------------------------------------------

CREATE TABLE generation_request_interactions (
    request_id      UUID        NOT NULL,
    -- The request's created_at: the partition key, so a form is dropped with its request.
    created_at      TIMESTAMPTZ NOT NULL,
    tenant_key      TENANT_KEY  NOT NULL,
    trigger         VARCHAR(16) NOT NULL CONSTRAINT chk_interactions_trigger CHECK (trigger IN ('INCOMPLETE', 'ALWAYS')),
    -- When the request stops waiting for the person (application time); NULL when nothing was asked.
    -- Links to the form are short-lived signed codes, nothing of them is stored here.
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
    -- Who asked: the caller's application, or its user id without one. Only that caller gets a
    -- link from the job status; anyone else who may read the job sees it without one. NULL when
    -- nothing authenticated asked, and then nobody gets a link that way.
    requested_by    TEXT,
    PRIMARY KEY (request_id, created_at)
) PARTITION BY RANGE (created_at);

-- The form page finds its request by id (the link's path), without knowing its partition.
CREATE INDEX idx_interactions_request ON generation_request_interactions (request_id);

-- No initial partitions: PartitionMaintenanceScheduler creates the current and next month at
-- startup, as for document_content.

-- A form goes with its request. Requests are deleted by the cascades from their tenant, template,
-- variant, version or environment, and the form holds personal data (the caller's data and texts),
-- so it must not stay behind. A foreign key cannot say this: retention drops an old request partition
-- with DROP TABLE ... CASCADE, which drops a foreign key between partitioned tables whole, silently.
-- Retention drops this table's partitions on the same schedule; this trigger covers row deletes.
CREATE FUNCTION delete_generation_request_interaction() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    DELETE FROM generation_request_interactions WHERE request_id = OLD.id AND created_at = OLD.created_at;
    RETURN OLD;
END;
$$;

COMMENT ON FUNCTION delete_generation_request_interaction() IS 'Deletes the form of a deleted generation request (trg_requests_delete_interaction).';

-- Only requests created with interactive have a form; every other delete skips the lookup.
CREATE TRIGGER trg_requests_delete_interaction
    AFTER DELETE ON document_generation_requests
    FOR EACH ROW WHEN (OLD.has_form)
    EXECUTE FUNCTION delete_generation_request_interaction();

-- A link's code works once: the nonce of every redeemed code is kept until the code would have
-- expired anyway, then swept. Codes live minutes, so the table stays small.
CREATE TABLE generation_form_link_redemptions (
    nonce      CHAR(22)    NOT NULL PRIMARY KEY,
    expires_at TIMESTAMPTZ NOT NULL
);

COMMENT ON TABLE generation_form_link_redemptions IS 'Nonces of redeemed interactive form link codes, so a code opens the form once; swept after expires_at.';

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
    ADD COLUMN allowed_return_urls TEXT[] NOT NULL DEFAULT '{}';

COMMENT ON COLUMN applications.allowed_return_urls IS 'Starts of the URLs (scheme://host[:port][/path]) an interactive generation request of this application may name as returnUrl or link: same origin, and a path under the entry''s at a slash (ReturnUrlPrefix).';
