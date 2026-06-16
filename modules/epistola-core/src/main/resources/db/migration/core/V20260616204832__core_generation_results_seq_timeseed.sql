-- Keep `generation_results.sequence` MONOTONIC ACROSS A DESTRUCTIVE DB RESET.
--
-- The collect protocol is a Kafka-style log: every emitted result gets a globally
-- monotonic `sequence` (the BIGSERIAL `generation_results_sequence_seq`), and each
-- external consumer (e.g. the Valtimo plugin) persists a high-water cursor IN ITS
-- OWN DATABASE and polls `WHERE sequence > cursor`. The protocol's one correctness
-- assumption is that the sequence only ever moves forward.
--
-- A destructive reset of THIS database breaks that assumption asymmetrically: a
-- Flyway `clean` (the embedded-mode self-heal on a migration checksum mismatch)
-- drops the schema and restarts the BIGSERIAL at 1, but the consumer's cursor
-- survives in its own database. The consumer then asks for `sequence > <old
-- high-water>` and every freshly emitted result (sequence 1, 2, ...) falls at or
-- below it and is NEVER delivered — silently. (Observed in tst: consumer cursor
-- pinned at 4 while the reset sequence sat at 2; Valtimo intermediate catch
-- events never completed because the document-generated results never arrived.)
--
-- Fix: seed the sequence from wall-clock epoch-MILLISECONDS at (re)initialisation.
-- This versioned migration re-runs whenever the schema is rebuilt (fresh install
-- OR after a `clean`), so the floor is always the reinit time. Because wall-clock
-- only moves forward, any post-reset sequence strictly exceeds every sequence the
-- previous incarnation issued (we would have to emit >1000 results/SECOND on
-- average between resets for the +1 increments to catch up to elapsed time) — so a
-- stale consumer cursor is always below the new results and consumption self-heals
-- with no client change.
--
-- Milliseconds, not microseconds: keeps values under JS's 2^53 safe-integer limit
-- for ~285k years and at `Date.now()` magnitude. `sequence` is an internal cursor
-- token (opaque to clients; documents/jobs are UUID-keyed), so the large magnitude
-- has no storage, index, or comparison cost — a bigint is a fixed 8 bytes
-- regardless of value, and bigint headroom is ~9.2e18 (overflow ~year 292,000,000).
--
-- GREATEST(last_value, now) only ever RAISES the floor, never lowers it: a normal
-- restart never re-runs this migration, and on the off chance the sequence is
-- already ahead it is left untouched.
SELECT setval(
    'generation_results_sequence_seq',
    GREATEST(
        (SELECT last_value FROM generation_results_sequence_seq),
        (EXTRACT(EPOCH FROM clock_timestamp()) * 1000)::bigint
    ),
    true
);
