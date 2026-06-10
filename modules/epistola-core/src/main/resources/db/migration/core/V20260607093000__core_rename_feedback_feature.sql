-- The 'feedback' feature toggle is renamed to 'support-feedback' to reflect that
-- it gates the support-tier feedback integration (sync to epistola-hub), now
-- surfaced under the Support nav section. Carry over any per-tenant overrides so
-- a tenant that explicitly toggled it keeps its setting.
UPDATE feature_toggles SET feature_key = 'support-feedback' WHERE feature_key = 'feedback';
