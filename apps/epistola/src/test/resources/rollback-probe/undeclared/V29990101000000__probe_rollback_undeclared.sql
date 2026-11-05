-- SPDX-FileCopyrightText: Epistola Nederland B.V.
--
-- SPDX-License-Identifier: AGPL-3.0-only

-- Probe used only by SchemaRollbackGuardIT: a migration from a newer build that declares nothing
-- about older code, so it counts as not rollback-compatible. Applied to a throwaway database, never
-- to the shared test database.
CREATE TABLE rollback_probe (id INT);
