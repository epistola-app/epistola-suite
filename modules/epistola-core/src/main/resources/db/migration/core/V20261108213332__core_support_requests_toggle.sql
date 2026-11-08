-- rollback-compatible: false
-- reason: Renames a feature key; code predating it reads overrides under the old key.
-- SPDX-FileCopyrightText: Epistola Nederland B.V.
--
-- SPDX-License-Identifier: AGPL-3.0-only

-- Feedback became support requests (ADR 0053 §2), and its toggle key with it: a tenant's override
-- keeps applying under the new key.
UPDATE feature_toggles SET feature_key = 'support-requests' WHERE feature_key = 'support-feedback';
