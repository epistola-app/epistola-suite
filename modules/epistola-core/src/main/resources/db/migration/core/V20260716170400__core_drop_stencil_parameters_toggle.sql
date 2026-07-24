-- SPDX-FileCopyrightText: Epistola Nederland B.V.
--
-- SPDX-License-Identifier: AGPL-3.0-only

-- backup-restore-compatibility: backward=true forward=true
-- reason: Data-only cleanup of feature_toggles; no structural change to any backed-up table, so a
-- backup restores cleanly across this migration in either direction.
--
-- The `stencil-parameters` feature toggle is retired (issue #668): parameters are an intrinsic
-- property of every stencil (the parameter set is simply sometimes empty), so a global on/off
-- toggle is redundant. Delete any orphaned tenant override rows so no dangling state remains.
DELETE FROM feature_toggles
WHERE feature_key = 'stencil-parameters';
