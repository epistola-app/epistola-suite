-- rollback-compatible: true
-- SPDX-FileCopyrightText: Epistola Nederland B.V.
--
-- SPDX-License-Identifier: AGPL-3.0-only

-- Probe used only by SchemaRollbackGuardIT: a migration that fails, so the step that runs it fails
-- after applying the migrations before it.
SELECT 1 / 0;
