-- rollback-compatible: true
-- reason: Adds nullable columns and an index, which code predating them never reads.
-- SPDX-FileCopyrightText: Epistola Nederland B.V.
--
-- SPDX-License-Identifier: AGPL-3.0-only

-- The message as written in the code, before its arguments were filled in, so a log excerpt can be
-- pseudonymised; and lookups by trace id, for the log lines of one request.
ALTER TABLE application_log ADD COLUMN message_pattern TEXT;
COMMENT ON COLUMN application_log.message_pattern IS 'The message as written in the code, before its arguments were filled in; NULL for lines stored before it was kept.';

-- Who the line was written for, so a user reads the lines of their own requests only, whatever trace
-- ids they name: trace ids are no secret (clients may choose them). No FK: log lines outlive users.
ALTER TABLE application_log ADD COLUMN user_id UUID;
COMMENT ON COLUMN application_log.user_id IS 'The user whose request wrote the line; NULL for system and background work, and lines stored before it was kept.';

CREATE INDEX idx_application_log_trace ON application_log (trace_id) WHERE trace_id IS NOT NULL;
