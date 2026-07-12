-- Progress heartbeat for load test runs (issue #725).
--
-- Stale-run recovery previously keyed off claimed_at age alone, so "stale" meant
-- *old*, not *abandoned*: a healthy long run that was still making progress could be
-- reset to PENDING and re-executed by another node, submitting a second full batch
-- and corrupting the run's metrics.
--
-- last_progress_at is stamped by the executor's poll loop (every ~500ms) and on
-- claim, so recovery can key off "no progress within the timeout" instead of claim
-- age. A run whose executor is alive and polling is never recovered regardless of
-- total duration; only a genuinely abandoned run (dead executor) is.

ALTER TABLE load_test_runs
    ADD COLUMN last_progress_at TIMESTAMPTZ;

COMMENT ON COLUMN load_test_runs.last_progress_at IS
    'Most recent progress heartbeat from the executing node (stamped on claim and on every poll cycle). Stale-run recovery keys off this instead of claimed_at so healthy long runs are not re-executed. NULL until first claimed.';
