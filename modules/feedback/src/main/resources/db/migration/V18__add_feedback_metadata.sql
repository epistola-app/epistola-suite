-- Add metadata column to store browser/client information captured at submission time
ALTER TABLE feedback ADD COLUMN metadata JSONB;

COMMENT ON COLUMN feedback.metadata IS 'Client metadata captured at submission: browser, viewport, app version, etc.';
